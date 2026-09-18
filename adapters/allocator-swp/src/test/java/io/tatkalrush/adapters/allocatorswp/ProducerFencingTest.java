package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <b>DD-006 at the mechanism it depends on</b>: two producers sharing one
 * {@code transactional.id} can never both commit.
 *
 * <p>{@link KafkaSeatAllocator} relies on this for §9.3's ownership fencing, but
 * proving it against a real rebalance between two live allocator instances would
 * mean controlling exactly when Kafka reassigns a specific partition — not
 * deterministic in a unit test, and squarely a chaos-suite concern (T-C7,
 * milestone 4) once there is a second replica to rebalance against. This test
 * isolates the one property the whole scheme rests on, directly: initializing a
 * second transactional producer with the same id fences the first one's
 * in-flight transaction, and the fenced producer's commit throws rather than
 * silently succeeding.
 */
class ProducerFencingTest {

    private static final String TOPIC = "fencing-test-topic";
    private static final String TRANSACTIONAL_ID = "partition-owner-fencing-test-0";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        var adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private KafkaProducer<String, String> transactionalProducer() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, TRANSACTIONAL_ID);
        return new KafkaProducer<>(props);
    }

    @Test
    @DisplayName(
            "T-C7's mechanism: a new owner's initTransactions() fences the old one, "
                    + "whose commit then throws and publishes nothing")
    void secondProducerFencesTheFirst() {
        KafkaProducer<String, String> stale = transactionalProducer();
        stale.initTransactions();
        stale.beginTransaction();
        stale.send(new ProducerRecord<>(TOPIC, "key", "the stale owner's write - must never become visible"));

        // The new owner taking over the partition. Per DD-006: this bumps the
        // producer epoch at the broker and fences whichever producer held this
        // transactional.id before - exactly what a rebalance's new consumer does
        // before it starts applying commands.
        KafkaProducer<String, String> fresh = transactionalProducer();
        fresh.initTransactions();

        // The stale owner does not learn it has been fenced until it tries to
        // commit - it already applied the command to its in-memory PartitionOwner
        // by this point in the real flow (DD-007: heap can run ahead of the log).
        assertThrows(ProducerFencedException.class, stale::commitTransaction);

        try {
            fresh.beginTransaction();
            fresh.send(new ProducerRecord<>(TOPIC, "key", "the real owner's write"));
            fresh.commitTransaction();
        } finally {
            fresh.close(Duration.ZERO);
        }

        assertEquals(
                List.of("the real owner's write"),
                readAllCommitted(),
                "the fenced producer's write must not appear, even though it sent before the real owner");

        stale.close(Duration.ZERO);
    }

    private List<String> readAllCommitted() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "fencing-test-reader");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            var values = new java.util.ArrayList<String>();
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
                if (!values.isEmpty()) {
                    // Give a little longer in case the (wrongly) fenced write is
                    // about to arrive too - it should not.
                    ConsumerRecords<String, String> more = consumer.poll(Duration.ofSeconds(2));
                    for (ConsumerRecord<String, String> record : more) {
                        values.add(record.value());
                    }
                    break;
                }
            }
            assertTrue(!values.isEmpty(), "expected at least the real owner's write to be readable");
            return values;
        }
    }
}
