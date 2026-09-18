package io.tatkalrush.adapters.allocatorswp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.AvailabilitySnapshot;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * <b>Strategy B, through milestone 5</b> (§9.3): checkpointing, replay-based
 * recovery, the dedup/reply cache, and cross-replica hold routing.
 *
 * <p>Milestones 1 and 2 proved the request/reply round trip and producer-epoch
 * fencing. Milestone 3 is what lets a partition survive its owner dying:
 *
 * <ul>
 *   <li><b>Every state-changing command's outcome is checkpointed</b>, per pool,
 *       to the {@code checkpoints} table (migration V7) — generation-guarded so
 *       a zombie owner (fenced from Kafka, never fenced from Postgres) cannot
 *       overwrite a fresher checkpoint with a stale one (DD-013, T-C10). See
 *       {@link JdbcCheckpointStore}.
 *   <li><b>A newly-assigned partition replays {@code booking-events} from the
 *       beginning</b> before it will serve a single command — see
 *       {@link #replayPartition}. Kafka's rebalance protocol blocks the next
 *       {@code poll()} until {@code onPartitionsAssigned} returns, which is
 *       exactly §9.3's "commands received during replay are buffered, not
 *       rejected": nothing needs to actively buffer anything, because nothing
 *       is consumed from {@code booking-commands} for this partition until
 *       replay finishes.
 * </ul>
 *
 * <p>Milestone 4 adds {@link DedupCache}: a bounded, time-evicted
 * {@code commandId -> reply} cache per owned partition (DD-009), rebuilt from
 * the same replay pass. It protects against a scenario replay alone does not
 * cover — the owner completed a command and committed its transaction, but the
 * reply never reached the originating replica (that replica died, or the
 * network dropped it), and whatever called {@link #allocate} retries with the
 * <em>same</em> request. Without this, that retry either throws (the domain
 * refuses a duplicate {@code holdId}) or, on an implementation without that
 * guard, allocates a second time under the same identity. See
 * {@link #processBatch} for where a cache hit short-circuits {@link #apply}
 * entirely — no re-application, no new WAL event, just the same reply again.
 *
 * <p>Milestone 5 closes a gap the earlier milestones left open deliberately:
 * {@link #release} and {@link #confirm} take only a {@code holdId}, with
 * nothing to route by, because their callers — {@code CancelBooking.release()}
 * and {@code ExpireHolds}'s sweep — often do not have the pool to hand either.
 * A stateless multi-replica deployment does not guarantee the replica that ran
 * {@code HoldSeats} is the one that later cancels or reaps the same hold, so
 * milestone 1's per-JVM {@link #holdRouting} map is wrong exactly when it
 * matters. {@link #resolvePoolKey} adds the fallback: {@code hold_routing}
 * (migration V10), a durable index {@link #allocate} writes to
 * <b>synchronously</b> — on the caller's own thread, after the Kafka round
 * trip already succeeded — because a release or confirm for that exact hold
 * can legitimately arrive on a different replica the instant {@link #allocate}
 * returns, and an async write would then race a real caller rather than just
 * add latency nobody asked to trade for. {@link #release} and {@link #confirm}
 * delete the row afterward off the background writer instead — safe to lag,
 * since a hold that resolves twice is already idempotent at the owner. A
 * same-replica call still never touches Postgres at all: only a local miss in
 * {@link #holdRouting} reaches {@link #resolvePoolKey}'s fallback.
 *
 * <p>This directory answers a different question from the "which pools live
 * on which partition" one the scope cut below is about: this one is
 * {@code holdId -> pool}, needed to route a single hold's release or confirm;
 * that one would be {@code partition -> [pool]}, needed to bound replay.
 * Solving this one did not require solving that one.
 *
 * <h2>A scope cut, made deliberately and stated plainly</h2>
 *
 * <p>{@link #replayPartition} always starts from offset 0 — it does not yet
 * consult the checkpoint it just wrote for this partition's pools to skip ahead.
 * The reason is a real gap in what's tracked: many pools hash onto one Kafka
 * partition (§9.3), the {@code checkpoints} table records one row per <b>pool</b>,
 * and nothing yet records <em>which pools live on which partition</em> — so a
 * newly-assigned owner has no list of checkpoints to even look up before it has
 * replayed anything and discovered its pools from {@code Provisioned} events.
 * Solving that means adding a directory, which is separate scope. Recovery here
 * is therefore <b>correct but not yet bounded</b> — NFR-8's ≤2 s warm-recovery
 * target is not measured or claimed by this milestone.
 */
public final class KafkaSeatAllocator implements SeatAllocator, AutoCloseable, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaSeatAllocator.class);

    private static final int REPLY_PARTITION = 0;

    /** Below this age, a pool's checkpoint is left alone (§9.3: "every 5 seconds"). */
    private static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(5);

    /**
     * §9.3: "roughly 60 seconds, not FR-19's 10 minutes, because replay
     * correctness lives in Postgres (FR-19)." This cache is a narrower, faster
     * guarantee - one in-flight command plus one retry - layered under FR-19's
     * broader one, not a replacement for it.
     */
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);

    /** §9.3: "~5,000 entries per hot partition" at P1. */
    private static final int DEDUP_MAX_ENTRIES = 5_000;

    private final String commandsTopic;
    private final String eventsTopic;
    private final String repliesTopic;
    private final String bootstrapServers;
    private final Duration replyTimeout;
    private final ObjectMapper json;
    private final CheckpointStore checkpointStore;

    private final ConcurrentHashMap<Integer, PartitionOwner> owners = new ConcurrentHashMap<>();

    /** One transactional producer per currently-owned partition; see the class Javadoc. */
    private final ConcurrentHashMap<Integer, KafkaProducer<String, String>> ownerProducers =
            new ConcurrentHashMap<>();

    /** One dedup/reply cache per currently-owned partition (§9.3, DD-009). */
    private final ConcurrentHashMap<Integer, DedupCache> dedupCaches = new ConcurrentHashMap<>();

    /** §9.3's {@code orphaned_replies_total}: a reply nobody was still waiting for. */
    private final java.util.concurrent.atomic.AtomicLong orphanedRepliesTotal =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * This JVM's tenure number for each partition it currently owns (DD-013's
     * {@code myGeneration}) — the wall-clock millisecond of assignment. A
     * production version bootstrapping generation from Postgres would be immune
     * to clock skew across machines; milestone 3 accepts that risk to avoid
     * needing the pool-to-partition directory noted in the class Javadoc, since
     * that directory is what a Postgres-backed bootstrap would also require.
     */
    private final ConcurrentHashMap<Integer, Long> generationByPartition = new ConcurrentHashMap<>();

    /** Debounces checkpoint writes per pool (§9.3: "every 5 seconds"). */
    private final ConcurrentHashMap<String, Instant> lastCheckpointAt = new ConcurrentHashMap<>();

    /**
     * DD-013's off-the-consumer-thread writer, for both checkpoint writes and
     * {@link #holdRoutingStore} maintenance (milestone 5) — neither may block
     * the thread that is single-handedly serving every command on a partition.
     */
    private final ExecutorService backgroundWriter =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("swp-background-writer").unstarted(r));

    private final HoldRoutingStore holdRoutingStore;

    /**
     * The fast path: populated by this JVM's own successful allocates, and
     * cleared on its own successful releases/confirms. A hit here means a
     * same-replica call, which is the common case and never touches Postgres.
     * See the class Javadoc for the miss path.
     */
    private final ConcurrentHashMap<String, String> holdRouting = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CompletableFuture<PartitionReply>> pending =
            new ConcurrentHashMap<>();

    /**
     * Not {@code final}: all five of these are built in {@link #start()}, not
     * the constructor — see that method's Javadoc for why. {@code commandProducer}
     * is client-side; publishing a command is never part of the owner's
     * transaction.
     */
    private KafkaConsumer<String, String> commandConsumer;

    private KafkaConsumer<String, String> replyConsumer;
    private KafkaProducer<String, String> commandProducer;
    private Thread commandLoopThread;
    private Thread replyLoopThread;
    private volatile boolean running = false;

    public KafkaSeatAllocator(
            String bootstrapServers,
            String commandsTopic,
            String eventsTopic,
            String repliesTopic,
            Duration replyTimeout,
            CheckpointStore checkpointStore,
            HoldRoutingStore holdRoutingStore) {
        this.bootstrapServers = bootstrapServers;
        this.commandsTopic = commandsTopic;
        this.eventsTopic = eventsTopic;
        this.repliesTopic = repliesTopic;
        this.replyTimeout = replyTimeout;
        this.checkpointStore = checkpointStore;
        this.holdRoutingStore = holdRoutingStore;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
        // Deliberately no I/O here — see start().
    }

    /**
     * {@link SmartLifecycle}: does the constructor's old work, but only once
     * Spring has finished refreshing the context.
     *
     * <p>{@code HoldReaper} exists in this shape for a reason worth repeating
     * here rather than only there: starting background work inside a
     * {@code @Bean} factory method — the constructor, for a plain eager bean —
     * let its first sweep ask Postgres for a connection before Flyway had
     * migrated the schema, and on a cold stack that timed out and failed the
     * whole replica's boot. This class does the same thing HoldReaper does for
     * the same reason: nothing here runs until the container calls
     * {@link #start}, which happens after every other bean — including
     * Flyway — has finished initialising.
     *
     * <p>Callers that are not Spring (every test in this module) must call
     * this exactly once, immediately after construction, before using the
     * instance as a {@link SeatAllocator}.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        this.commandProducer = new KafkaProducer<>(producerProps);

        this.commandConsumer = newConsumer(bootstrapServers, "partition-owners", "read_uncommitted");
        this.commandConsumer.subscribe(List.of(commandsTopic), new OwnershipListener());

        // Unique group per instance: this replica must see every reply, regardless
        // of which replica's owner produced it. read_committed: §9.3 - a reply
        // published inside a transaction that later aborts (fencing) must never
        // be visible, or a client would get an answer its owner did not keep.
        this.replyConsumer =
                newConsumer(bootstrapServers, "reply-consumer-" + UUID.randomUUID(), "read_committed");
        this.replyConsumer.subscribe(List.of(repliesTopic));

        running = true;
        this.commandLoopThread = Thread.ofPlatform().name("swp-command-owner").start(this::commandLoop);
        this.replyLoopThread = Thread.ofPlatform().name("swp-reply-consumer").start(this::replyLoop);
    }

    private static KafkaConsumer<String, String> newConsumer(
            String bootstrapServers, String groupId, String isolationLevel) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolationLevel);
        return new KafkaConsumer<>(props);
    }

    private KafkaProducer<String, String> newTransactionalProducer(int partition) {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // DD-006: this id is the fencing token. Two producers sharing it can never
        // both commit - the second's initTransactions() always wins.
        props.put(
                ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                "partition-owner-" + commandsTopic + "-" + partition);
        var producer = new KafkaProducer<String, String>(props);
        producer.initTransactions();
        return producer;
    }

    /** Assigns and revokes owners and their transactional producers with the partition (§9.3, DD-007). */
    private final class OwnershipListener implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            for (TopicPartition tp : partitions) {
                // Blocks the next poll() of booking-commands for this partition
                // until replay finishes - see the class Javadoc on why that alone
                // satisfies "commands buffered, not rejected" with no buffer to
                // write.
                ReplayResult replayed = replayPartition(tp.partition());
                generationByPartition.put(tp.partition(), System.currentTimeMillis());
                owners.put(tp.partition(), replayed.owner());
                dedupCaches.put(tp.partition(), replayed.dedupCache());
                ownerProducers.put(tp.partition(), newTransactionalProducer(tp.partition()));
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            discard(partitions);
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            discard(partitions);
        }

        private void discard(Collection<TopicPartition> partitions) {
            for (TopicPartition tp : partitions) {
                // DD-007: the owner's heap may be ahead of the log by an unknown
                // amount. Discard outright rather than keep serving from it.
                owners.remove(tp.partition());
                dedupCaches.remove(tp.partition());
                generationByPartition.remove(tp.partition());
                KafkaProducer<String, String> producer = ownerProducers.remove(tp.partition());
                if (producer != null) {
                    closeQuietly(producer);
                }
            }
        }
    }

    private record ReplayResult(PartitionOwner owner, DedupCache dedupCache) {}

    /**
     * Rebuilds a partition's state from {@code booking-events}, from the
     * beginning (see the class Javadoc for why not from a checkpoint yet) — and
     * alongside it, the dedup cache (§9.3: "the map is rebuilt from the WAL
     * during recovery replay").
     *
     * <p>Uses a manually-assigned consumer, not the consumer group: replay needs
     * to seek to an exact offset and stop at an exact one, which a group
     * membership's rebalance-driven assignment does not give control over.
     */
    private ReplayResult replayPartition(int partition) {
        var owner = new PartitionOwner();
        var dedup = new DedupCache(DEDUP_MAX_ENTRIES, DEDUP_TTL);
        var tp = new TopicPartition(eventsTopic, partition);

        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        try (KafkaConsumer<String, String> replay = new KafkaConsumer<>(props)) {
            replay.assign(List.of(tp));
            replay.seekToBeginning(List.of(tp));

            long endOffset = replay.endOffsets(List.of(tp)).get(tp);
            if (endOffset == 0) {
                log.info("partition {}: no events to replay", partition);
                return new ReplayResult(owner, dedup);
            }

            Instant replayClock = Instant.now();
            int applied = 0;
            while (replay.position(tp) < endOffset) {
                ConsumerRecords<String, String> records = replay.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    PartitionEvent event = decode(record.value(), PartitionEvent.class);
                    owner.applyEvent(event);

                    // Reconstruct the dedup entry too, expiring relative to WHEN
                    // this event happened, not to now - an entry from 55s ago has
                    // 5s of protection left, not a fresh 60 (§9.3).
                    Instant happenedAt = Instant.ofEpochMilli(record.timestamp());
                    Instant expiresAt = happenedAt.plus(DEDUP_TTL);
                    if (expiresAt.isAfter(replayClock)) {
                        dedup.putWithExpiry(event.commandId(), deriveReply(event), expiresAt, replayClock);
                    }
                    applied++;
                }
                if (records.isEmpty() && replay.position(tp) < endOffset) {
                    // Nothing arrived this poll but we have not reached the
                    // recorded end offset - keep trying; do not spin unbounded on
                    // a topic that will never produce it (retention deleted the
                    // range) without at least logging that this happened.
                    log.debug("partition {}: waiting for replay to reach offset {}", partition, endOffset);
                }
            }
            log.info("partition {}: replayed {} events up to offset {}", partition, applied, endOffset);
        }
        return new ReplayResult(owner, dedup);
    }

    /**
     * The reply an event's originating command must have produced — the
     * inverse of {@link #apply}'s reply-building for each case, used only to
     * reseed the dedup cache from history. There is no ambiguity to resolve:
     * every field either variant needs, the other already carries.
     */
    private static PartitionReply deriveReply(PartitionEvent event) {
        return switch (event) {
            case PartitionEvent.Provisioned e -> new PartitionReply.Ack(e.commandId());
            case PartitionEvent.Allocated e ->
                    new PartitionReply.Allocated(e.commandId(), e.berthIds(), e.range(), e.expiresAt());
            case PartitionEvent.Released e -> new PartitionReply.Ack(e.commandId());
            case PartitionEvent.Confirmed e ->
                    new PartitionReply.Confirmed(e.commandId(), e.bookingId(), e.berthIds());
            case PartitionEvent.ReleaseConfirmed e ->
                    new PartitionReply.ReleaseConfirmedAck(e.commandId(), e.cleared());
            case PartitionEvent.Reaped e -> new PartitionReply.ReapedCount(e.commandId(), e.reaped());
        };
    }

    // ---------------------------------------------------------- provisioning

    public void provision(PoolKey pool, int berthCount, int segmentCount, List<Long> berthIds) {
        provision(pool, berthCount, segmentCount, berthIds, List.of());
    }

    /**
     * @param occupiedMasks confirmed occupancy per ordinal, or empty for a fresh
     *     pool (milestone 7) — see {@code ops/pool-warmup}'s Strategy B
     *     provisioner, which is the actual caller for a non-empty one.
     */
    public void provision(
            PoolKey pool, int berthCount, int segmentCount, List<Long> berthIds, List<Long> occupiedMasks) {
        var command =
                new PartitionCommand.Provision(
                        newCommandId(),
                        REPLY_PARTITION,
                        pool,
                        berthCount,
                        segmentCount,
                        berthIds,
                        occupiedMasks);
        send(pool.keySuffix(), command);
    }

    // -------------------------------------------------------------- allocate

    @Override
    public AllocationResult allocate(AllocationRequest request) {
        var command =
                new PartitionCommand.Allocate(
                        request.holdId(), // DD-009: commandId IS the Idempotency-Key
                        REPLY_PARTITION,
                        request.pool(),
                        request.range(),
                        request.passengerCount(),
                        request.holdId(),
                        request.now(),
                        request.ttlMillis());

        PartitionReply reply = send(request.pool().keySuffix(), command);

        return switch (reply) {
            case PartitionReply.Allocated a -> {
                String poolKey = request.pool().keySuffix();
                holdRouting.put(request.holdId(), poolKey);
                // Synchronous, unlike the checkpoint write DD-013 describes,
                // and deliberately so: a release or confirm for this exact
                // hold, on a DIFFERENT replica, can legitimately arrive the
                // instant this call returns (a client that holds then
                // immediately cancels). An async write here is not a latency
                // optimisation, it is a race - the very first version of this
                // milestone had exactly that bug, caught by
                // CrossReplicaHoldRoutingTest asserting the released berth was
                // actually free on a different instance, not by inspection.
                holdRoutingStore.save(request.holdId(), poolKey);
                yield new AllocationResult.Allocated(
                        request.holdId(), a.berthIds(), a.range(), a.expiresAt());
            }
            case PartitionReply.Unavailable u ->
                    new AllocationResult.Unavailable(u.available(), u.requested());
            default -> throw unexpectedReply("allocate", reply);
        };
    }

    // --------------------------------------------------------------- release

    @Override
    public void release(String holdId) {
        String poolKey = resolvePoolKey(holdId);
        if (poolKey == null) {
            return; // unrouted even after the durable fallback: already gone
        }
        holdRouting.remove(holdId);
        send(poolKey, new PartitionCommand.Release(newCommandId(), REPLY_PARTITION, holdId));
        backgroundWriter.submit(() -> holdRoutingStore.delete(holdId));
    }

    /**
     * The fast path first (this JVM's own allocation), then the durable
     * directory (milestone 5) — the only case that reaches Postgres is a
     * caller resolving a hold a <em>different</em> replica created, which is
     * exactly {@code CancelBooking.release()}'s and {@code ExpireHolds}'
     * situation: neither runs on whichever replica happened to run
     * {@code HoldSeats}.
     */
    private String resolvePoolKey(String holdId) {
        String local = holdRouting.get(holdId);
        return local != null ? local : holdRoutingStore.load(holdId).orElse(null);
    }

    @Override
    public int releaseConfirmed(PoolKey pool, SegmentRange range, List<Long> berthIds) {
        if (berthIds.isEmpty()) {
            return 0;
        }
        var command =
                new PartitionCommand.ReleaseConfirmed(
                        newCommandId(), REPLY_PARTITION, pool, range, berthIds);
        PartitionReply reply = send(pool.keySuffix(), command);
        return switch (reply) {
            case PartitionReply.ReleaseConfirmedAck ack -> ack.cleared();
            default -> throw unexpectedReply("releaseConfirmed", reply);
        };
    }

    // --------------------------------------------------------------- confirm

    @Override
    public ConfirmResult confirm(String holdId, long bookingId) {
        String poolKey = resolvePoolKey(holdId);
        if (poolKey == null) {
            return new ConfirmResult.HoldExpired(holdId);
        }

        var command = new PartitionCommand.Confirm(newCommandId(), REPLY_PARTITION, holdId, bookingId);
        PartitionReply reply = send(poolKey, command);

        return switch (reply) {
            case PartitionReply.Confirmed c -> {
                holdRouting.remove(holdId);
                backgroundWriter.submit(() -> holdRoutingStore.delete(holdId));
                yield new ConfirmResult.Confirmed(c.bookingId(), c.berthIds());
            }
            case PartitionReply.HoldExpired ignored -> {
                holdRouting.remove(holdId);
                backgroundWriter.submit(() -> holdRoutingStore.delete(holdId));
                yield new ConfirmResult.HoldExpired(holdId);
            }
            default -> throw unexpectedReply("confirm", reply);
        };
    }

    // ---------------------------------------------------------- availability

    @Override
    public AvailabilitySnapshot availability(PoolKey pool, SegmentRange range) {
        var command = new PartitionCommand.Availability(newCommandId(), REPLY_PARTITION, pool, range);
        PartitionReply reply = send(pool.keySuffix(), command);
        return switch (reply) {
            case PartitionReply.AvailabilityAnswer a ->
                    new AvailabilitySnapshot(pool, range, a.freeBerths(), false);
            default -> throw unexpectedReply("availability", reply);
        };
    }

    // ------------------------------------------------------------------ reap

    @Override
    public int reapExpired(Instant now) {
        int partitionCount = commandProducer.partitionsFor(commandsTopic).size();
        var futures = new ArrayList<CompletableFuture<PartitionReply>>(partitionCount);

        for (int partition = 0; partition < partitionCount; partition++) {
            String commandId = newCommandId();
            var command = new PartitionCommand.ReapExpired(commandId, REPLY_PARTITION, now);
            var future = new CompletableFuture<PartitionReply>();
            pending.put(commandId, future);
            commandProducer.send(
                    new ProducerRecord<>(commandsTopic, partition, null, commandId, encode(command)));
            futures.add(future);
        }
        commandProducer.flush();

        int total = 0;
        for (var future : futures) {
            PartitionReply reply = await(future);
            total += switch (reply) {
                case PartitionReply.ReapedCount r -> r.reaped();
                default -> throw unexpectedReply("reapExpired", reply);
            };
        }
        return total;
    }

    // ------------------------------------------------------------- transport

    private PartitionReply send(String key, PartitionCommand command) {
        var future = new CompletableFuture<PartitionReply>();
        pending.put(command.commandId(), future);
        commandProducer.send(new ProducerRecord<>(commandsTopic, key, encode(command)));
        commandProducer.flush();
        return await(future);
    }

    private PartitionReply await(CompletableFuture<PartitionReply> future) {
        try {
            return future.get(replyTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // §11.2 makes this RETRY_LATER/503 at the HTTP boundary; milestone 2
            // still has no replay to explain a lost reply (a fenced owner's batch
            // is exactly this case), so it fails loudly rather than guessing.
            throw new IllegalStateException("no reply within " + replyTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting partition owner reply", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("partition owner failed", e.getCause());
        }
    }

    private static IllegalStateException unexpectedReply(String operation, PartitionReply reply) {
        if (reply instanceof PartitionReply.Failed failed) {
            return new IllegalStateException(operation + ": owner failed: " + failed.message());
        }
        return new IllegalStateException(operation + ": unexpected reply " + reply);
    }

    private static String newCommandId() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------ owner loop

    /** What applying one command produced: always a reply, and an event iff state changed. */
    private record Outcome(PartitionReply reply, PartitionEvent event) {
        static Outcome of(PartitionReply reply) {
            return new Outcome(reply, null);
        }

        static Outcome of(PartitionReply reply, PartitionEvent event) {
            return new Outcome(reply, event);
        }
    }

    private record CheckpointCandidate(PoolKey pool, Future<RecordMetadata> event) {}

    private static java.util.Optional<PoolKey> poolOf(PartitionEvent event) {
        return switch (event) {
            case PartitionEvent.Provisioned e -> java.util.Optional.of(e.pool());
            case PartitionEvent.Allocated e -> java.util.Optional.of(e.pool());
            case PartitionEvent.Released e -> java.util.Optional.of(e.pool());
            case PartitionEvent.Confirmed e -> java.util.Optional.of(e.pool());
            case PartitionEvent.ReleaseConfirmed e -> java.util.Optional.of(e.pool());
            // Reaped names no single pool - see the class Javadoc's checkpoint
            // scope note. An idle-pool checkpoint going slightly stale from
            // reaping alone is the one case this milestone accepts as-is.
            case PartitionEvent.Reaped e -> java.util.Optional.empty();
        };
    }

    /**
     * Debounced, per pool (§9.3: "every 5 seconds"). The snapshot copy happens
     * here, on the consumer thread - cheap, and the only part of this that must
     * not race a later mutation (DD-013). The actual Postgres write is handed to
     * {@link #backgroundWriter} so it never sits on the hot path.
     */
    private void maybeCheckpoint(
            int partition, PartitionOwner owner, List<CheckpointCandidate> candidates) {
        Instant now = Instant.now();
        long generation = generationByPartition.getOrDefault(partition, 0L);

        // Last candidate per pool wins: only the highest offset in this batch
        // matters, and re-checkpointing the same pool twice in one batch buys
        // nothing.
        var latestPerPool = new java.util.LinkedHashMap<PoolKey, Future<RecordMetadata>>();
        for (CheckpointCandidate candidate : candidates) {
            latestPerPool.put(candidate.pool(), candidate.event());
        }

        for (var entry : latestPerPool.entrySet()) {
            PoolKey pool = entry.getKey();
            Instant last = lastCheckpointAt.get(pool.keySuffix());
            if (last != null && now.isBefore(last.plus(CHECKPOINT_INTERVAL))) {
                continue;
            }

            byte[] snapshot = owner.snapshotOf(pool);
            long offset;
            try {
                // Already resolved: commitTransaction() succeeded, which cannot
                // happen before every send in this transaction is durable.
                offset = entry.getValue().get().offset();
            } catch (Exception e) {
                log.warn("could not read committed offset for pool {}: {}", pool, e.toString());
                continue;
            }

            lastCheckpointAt.put(pool.keySuffix(), now);
            backgroundWriter.submit(
                    () -> {
                        try {
                            checkpointStore.save(pool.keySuffix(), offset, generation, snapshot);
                        } catch (RuntimeException e) {
                            log.warn("checkpoint write failed for pool {}: {}", pool, e.toString());
                        }
                    });
        }
    }

    private void commandLoop() {
        while (running) {
            ConsumerRecords<String, String> records;
            try {
                records = commandConsumer.poll(Duration.ofMillis(200));
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                continue;
            }
            if (records.isEmpty()) {
                continue;
            }

            for (TopicPartition tp : records.partitions()) {
                processBatch(tp.partition(), records.records(tp));
            }
        }
    }

    /**
     * One Kafka transaction per partition per poll batch (§9.3: "Commits are
     * amortised one transaction per consumed batch — the owner is single-threaded,
     * so batching is natural"). Every WAL append and every reply in this batch
     * commits together, or none of them do.
     */
    private void processBatch(int partition, List<ConsumerRecord<String, String>> batch) {
        PartitionOwner owner = owners.get(partition);
        KafkaProducer<String, String> transactional = ownerProducers.get(partition);
        DedupCache dedup = dedupCaches.get(partition);
        if (owner == null || transactional == null || dedup == null) {
            // Revoked between poll() returning and this loop reaching it. The
            // records are still committed at the consumer group level once this
            // poll cycle's offsets are auto-committed by whichever consumer holds
            // the partition next - nothing is lost, just not served by this JVM.
            log.debug("partition {} revoked mid-batch; deferring to the next owner", partition);
            return;
        }

        // (pool, event's committed offset) for every state change in this batch
        // that names a pool - the checkpoint trigger below runs only once the
        // transaction has actually committed.
        var checkpointCandidates = new ArrayList<CheckpointCandidate>();
        Instant now = Instant.now();

        try {
            transactional.beginTransaction();
            for (ConsumerRecord<String, String> record : batch) {
                PartitionCommand command = decode(record.value(), PartitionCommand.class);

                // DD-009: a retry reproduces the same commandId. Re-publish what
                // the owner answered last time rather than re-applying - for
                // Allocate specifically, re-applying would either throw (the hold
                // already exists) or, worse on a different implementation, pick a
                // second set of berths under the same holdId.
                java.util.Optional<PartitionReply> cached = dedup.get(command.commandId(), now);
                Outcome outcome;
                if (cached.isPresent()) {
                    outcome = Outcome.of(cached.get());
                } else {
                    outcome = apply(owner, command);
                    dedup.put(command.commandId(), outcome.reply(), now);
                }

                if (outcome.event() != null) {
                    Future<RecordMetadata> sent =
                            transactional.send(
                                    new ProducerRecord<>(
                                            eventsTopic, partition, command.commandId(), encode(outcome.event())));
                    poolOf(outcome.event())
                            .ifPresent(pool -> checkpointCandidates.add(new CheckpointCandidate(pool, sent)));
                }
                transactional.send(
                        new ProducerRecord<>(
                                repliesTopic,
                                command.replyPartition(),
                                command.commandId(),
                                encode(outcome.reply())));
            }
            transactional.commitTransaction();
            maybeCheckpoint(partition, owner, checkpointCandidates);
        } catch (ProducerFencedException e) {
            // DD-006's whole point: this owner no longer holds the partition at
            // the broker level. Neither the WAL appends nor the replies in this
            // batch became visible - the originating replicas' futures simply
            // time out, which is correct: this owner's heap may already be ahead
            // of anything it could have committed.
            log.warn("partition {} owner was fenced; discarding", partition);
            owners.remove(partition);
            dedupCaches.remove(partition);
            ownerProducers.remove(partition);
            closeQuietly(transactional);
        } catch (RuntimeException e) {
            log.warn("aborting transaction for partition {}: {}", partition, e.toString());
            try {
                transactional.abortTransaction();
            } catch (RuntimeException abortFailed) {
                log.warn("abort itself failed for partition {}: {}", partition, abortFailed.toString());
            }
        }
    }

    private Outcome apply(PartitionOwner owner, PartitionCommand command) {
        try {
            return switch (command) {
                case PartitionCommand.Provision c -> {
                    owner.provision(
                            c.pool(), c.berthCount(), c.segmentCount(), c.berthIds(), c.occupiedMasks());
                    yield Outcome.of(
                            new PartitionReply.Ack(c.commandId()),
                            new PartitionEvent.Provisioned(
                                    c.commandId(),
                                    c.pool(),
                                    c.berthCount(),
                                    c.segmentCount(),
                                    c.berthIds(),
                                    c.occupiedMasks()));
                }
                case PartitionCommand.Allocate c -> {
                    var request =
                            new AllocationRequest(
                                    c.pool(), c.range(), c.passengerCount(), c.holdId(), c.now(), c.ttlMillis());
                    yield switch (owner.allocate(request)) {
                        case AllocationResult.Allocated a ->
                                Outcome.of(
                                        new PartitionReply.Allocated(
                                                c.commandId(), a.berthIds(), a.range(), a.expiresAt()),
                                        new PartitionEvent.Allocated(
                                                c.commandId(), c.pool(), c.holdId(), a.range(), a.berthIds(), a.expiresAt()));
                        case AllocationResult.Unavailable u ->
                                Outcome.of(
                                        new PartitionReply.Unavailable(c.commandId(), u.available(), u.requested()));
                        case AllocationResult.QuotaLocked ql ->
                                Outcome.of(
                                        new PartitionReply.Failed(
                                                c.commandId(), "QuotaLocked is not produced by the allocator port"));
                    };
                }
                case PartitionCommand.Release c -> {
                    PoolKey pool = owner.poolOfHold(c.holdId());
                    owner.release(c.holdId());
                    yield pool == null
                            ? Outcome.of(new PartitionReply.Ack(c.commandId()))
                            : Outcome.of(
                                    new PartitionReply.Ack(c.commandId()),
                                    new PartitionEvent.Released(c.commandId(), pool, c.holdId()));
                }
                case PartitionCommand.Confirm c -> {
                    PoolKey pool = owner.poolOfHold(c.holdId());
                    yield switch (owner.confirm(c.holdId(), c.bookingId())) {
                        case ConfirmResult.Confirmed cf ->
                                Outcome.of(
                                        new PartitionReply.Confirmed(c.commandId(), cf.bookingId(), cf.berthIds()),
                                        new PartitionEvent.Confirmed(
                                                c.commandId(), pool, c.holdId(), cf.bookingId(), cf.berthIds()));
                        case ConfirmResult.HoldExpired ignored ->
                                Outcome.of(new PartitionReply.HoldExpired(c.commandId()));
                        case ConfirmResult.AllocationConflict ac ->
                                Outcome.of(
                                        new PartitionReply.Failed(
                                                c.commandId(), "AllocationConflict is not produced by the allocator port"));
                    };
                }
                case PartitionCommand.ReleaseConfirmed c -> {
                    int cleared = owner.releaseConfirmed(c.pool(), c.range(), c.berthIds());
                    yield cleared == 0
                            ? Outcome.of(new PartitionReply.ReleaseConfirmedAck(c.commandId(), 0))
                            : Outcome.of(
                                    new PartitionReply.ReleaseConfirmedAck(c.commandId(), cleared),
                                    new PartitionEvent.ReleaseConfirmed(
                                            c.commandId(), c.pool(), c.range(), c.berthIds(), cleared));
                }
                case PartitionCommand.Availability c -> {
                    var snapshot = owner.availability(c.pool(), c.range());
                    yield Outcome.of(new PartitionReply.AvailabilityAnswer(c.commandId(), snapshot.freeBerths()));
                }
                case PartitionCommand.ReapExpired c -> {
                    int reaped = owner.reapExpired(c.now());
                    yield reaped == 0
                            ? Outcome.of(new PartitionReply.ReapedCount(c.commandId(), 0))
                            : Outcome.of(
                                    new PartitionReply.ReapedCount(c.commandId(), reaped),
                                    new PartitionEvent.Reaped(c.commandId(), c.now(), reaped));
                }
            };
        } catch (RuntimeException e) {
            log.warn("partition owner failed applying {}: {}", command, e.toString());
            return Outcome.of(new PartitionReply.Failed(command.commandId(), String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------- reply loop

    private void replyLoop() {
        while (running) {
            ConsumerRecords<String, String> records;
            try {
                records = replyConsumer.poll(Duration.ofMillis(200));
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                continue;
            }
            for (ConsumerRecord<String, String> record : records) {
                PartitionReply reply = decode(record.value(), PartitionReply.class);
                CompletableFuture<PartitionReply> future = pending.remove(reply.commandId());
                if (future != null) {
                    future.complete(reply);
                } else {
                    // §9.3's orphaned_replies_total - the normal case once a
                    // different replica than the one that asked has died.
                    orphanedRepliesTotal.incrementAndGet();
                }
            }
        }
    }

    // ------------------------------------------------------------- metrics

    /** §9.3's {@code orphaned_replies_total}. */
    public long orphanedRepliesTotal() {
        return orphanedRepliesTotal.get();
    }

    /**
     * §9.3's {@code dedup_evictions_before_window_expiry_total}, summed across
     * this instance's currently-owned partitions. Not yet wired to Micrometer —
     * this allocator has no composition-root bean to register a meter with
     * until the multi-instance milestone wires {@code tatkal.allocator.strategy}
     * (see the M1 wrap-up); a plain counter is what an observer has to ask for
     * in the meantime.
     */
    public long dedupPrematureEvictionsTotal() {
        long total = 0;
        for (DedupCache cache : dedupCaches.values()) {
            total += cache.prematureEvictions();
        }
        return total;
    }

    /**
     * Test support only: clears every currently-owned partition's dedup cache.
     *
     * <p>Production correctness relies on {@link #DEDUP_TTL}'s 60-second window,
     * because a real client's {@code Idempotency-Key} is unique per logical
     * operation and never legitimately reappears with a different meaning. The
     * shared {@code SeatAllocatorContract} suite does not hold to that — it
     * reuses short literal holdIds like {@code "h"} and {@code "a"} across many
     * independent test methods, each with its own fresh pool, because Strategy
     * A's Redis keys are pool-namespaced and never collide on the bare string.
     * This owner's dedup cache is not pool-namespaced (a real commandId does not
     * carry a pool to namespace by), so within the TTL window a later test's
     * {@code "h"} hits an earlier test's cached reply for a completely different
     * pool. Waiting out the TTL between test methods would make the suite's
     * runtime hostage to a constant chosen for production; clearing between
     * tests is the honest fix; do not lower {@link #DEDUP_TTL} to "solve" this.
     */
    void clearDedupCachesForTesting() {
        dedupCaches.values().forEach(DedupCache::clear);
    }

    // --------------------------------------------------------------- codec

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to encode " + value, e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to decode " + type.getSimpleName(), e);
        }
    }

    private static void closeQuietly(KafkaProducer<String, String> producer) {
        try {
            producer.close(Duration.ZERO); // force-close: an in-flight transaction is already dead
        } catch (RuntimeException e) {
            log.debug("producer close after fencing threw (expected): {}", e.toString());
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        commandConsumer.wakeup();
        replyConsumer.wakeup();
        joinQuietly(commandLoopThread);
        joinQuietly(replyLoopThread);
        commandConsumer.close();
        replyConsumer.close();
        commandProducer.close();
        ownerProducers.values().forEach(KafkaSeatAllocator::closeQuietly);
        backgroundWriter.shutdown();
    }

    /** {@link SmartLifecycle}: the container calls this on shutdown, in place of a destroy method. */
    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(10).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
