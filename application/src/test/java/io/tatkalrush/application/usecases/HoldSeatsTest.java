package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IdempotencyStore;
import io.tatkalrush.application.ports.InMemorySeatAllocator;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.application.usecases.HoldSeats.HoldSeatsCommand;
import io.tatkalrush.application.usecases.HoldSeats.Result;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TatkalWindow;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code API-4}'s orchestration (FR-16 to FR-20, FR-29, FR-67a).
 *
 * <p>Fakes rather than containers. The mechanisms these ports stand for are
 * tested where they live — insert-first idempotency against real Postgres in
 * {@code IdempotencyRaceTest}, allocation against real Redis in the contract
 * suite. What is under test <em>here</em> is the ordering between them, which is
 * this class's entire content and which a container would only slow down.
 */
class HoldSeatsTest {

    private static final LocalDate JOURNEY = LocalDate.of(2026, 10, 2);
    private static final Instant TATKAL_OPEN = TatkalWindow.opensAt(JOURNEY, TravelClass.AC3);
    private static final Instant NOW = TATKAL_OPEN.plusSeconds(60);
    private static final long TTL = 120_000;

    private PoolKey pool;
    private InMemorySeatAllocator allocator;
    private FakeIdempotencyStore idempotency;
    private FakeBookingRepository bookings;
    private FakeScheduleQuery schedules;
    private HoldSeats holdSeats;

    @BeforeEach
    void setUp() {
        pool = new PoolKey(1L, TravelClass.AC3, QuotaType.TATKAL);
        allocator = new InMemorySeatAllocator();
        allocator.provision(pool, 3, 4);

        idempotency = new FakeIdempotencyStore();
        bookings = new FakeBookingRepository();
        schedules = new FakeScheduleQuery(pool, 3, 4, JOURNEY);

        holdSeats =
                new HoldSeats(
                        allocator, idempotency, bookings, schedules, new DirectUnitOfWork(),
                        TTL, 3);
    }

    /** Synthetic travellers, per FR-62. Names are the only personal data here. */
    private static List<Passenger> travellers(int count) {
        var list = new ArrayList<Passenger>();
        for (int i = 0; i < count; i++) {
            list.add(new Passenger("Passenger " + (i + 1), 30 + i, Passenger.Gender.O));
        }
        return list;
    }

    private HoldSeatsCommand command(String key, int passengers) {
        return new HoldSeatsCommand(
                pool, SegmentRange.of(0, 4), travellers(passengers), 7L, key, "hash-" + key, NOW);
    }

    // ------------------------------------------------------------- happy path

    @Nested
    @DisplayName("a successful hold")
    class HappyPath {

        @Test
        @DisplayName("allocates berths, freezes the fare, and opens a hold")
        void holdsBerths() {
            var held = assertInstanceOf(Result.Held.class, holdSeats.handle(command("k1", 2)));

            assertEquals(2, held.berthIds().size());
            assertEquals(NOW.plusMillis(TTL), held.expiresAt());

            // FR-67a: computed once, here, and frozen. 730 km in 3A TATKAL is
            // (730*285 + 4000 + 30000) per passenger, x2.
            assertEquals((208_050L + 4_000L + 30_000L) * 2, held.farePaise());

            var stored = bookings.findById(held.bookingId()).orElseThrow();
            assertEquals(BookingStatus.HELD, stored.status());
            assertEquals(Optional.empty(), stored.pnr(), "§6.4 issues the PNR at confirmation");
            assertEquals(held.farePaise(), stored.farePaise());
        }

        @Test
        @DisplayName("each passenger is paired with a berth, in order")
        void passengersReachTheRepositoryPairedWithBerths() {
            var held = assertInstanceOf(Result.Held.class, holdSeats.handle(command("k1", 3)));

            var stored = bookings.lastCreated();
            assertEquals(3, stored.passengerCount(), "derived from the list, not carried");
            assertEquals(held.berthIds(), stored.berthIds());
            assertEquals(
                    List.of("Passenger 1", "Passenger 2", "Passenger 3"),
                    stored.passengers().stream().map(Passenger::name).toList());
        }

        @Test
        @DisplayName("passengers and berths must be the same length (FR-6)")
        void mismatchedBerthsAreRefused() {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new BookingRepository.NewHeldBooking(
                                            pool,
                                            SegmentRange.of(0, 4),
                                            travellers(3),
                                            1000L,
                                            7L,
                                            NOW,
                                            "k",
                                            List.of(1L, 2L)));

            assertTrue(thrown.getMessage().contains("FR-6"), thrown.getMessage());
        }

        @Test
        @DisplayName("the idempotency claim is completed, so a replay can resolve")
        void claimIsCompleted() {
            var held = assertInstanceOf(Result.Held.class, holdSeats.handle(command("k1", 1)));
            assertEquals(
                    Optional.of(held.bookingId()), idempotency.bookingIdFor("k1"));
        }
    }

    // ------------------------------------------------------------ idempotency

    @Nested
    @DisplayName("FR-19: idempotency")
    class Idempotency {

        @Test
        @DisplayName("a replay returns the CURRENT representation, not a stored response")
        void replayReturnsCurrentState() {
            var first = assertInstanceOf(Result.Held.class, holdSeats.handle(command("k1", 1)));

            // The booking moves on underneath the key. A frozen response would
            // still claim HELD; FR-19 requires the truth at the moment of asking.
            bookings.setStatus(first.bookingId(), BookingStatus.EXPIRED);

            var replay =
                    assertInstanceOf(
                            Result.DuplicateRequest.class, holdSeats.handle(command("k1", 1)));

            assertEquals(first.bookingId(), replay.booking().id());
            assertEquals(
                    BookingStatus.EXPIRED,
                    replay.booking().status(),
                    "a replay after expiry must say EXPIRED, not repeat the original HELD");
        }

        @Test
        @DisplayName("a replay allocates nothing more")
        void replayDoesNotAllocate() {
            holdSeats.handle(command("k1", 2));
            int freeAfterFirst = allocator.availability(pool, SegmentRange.of(0, 4)).freeBerths();

            holdSeats.handle(command("k1", 2));
            holdSeats.handle(command("k1", 2));

            assertEquals(
                    freeAfterFirst,
                    allocator.availability(pool, SegmentRange.of(0, 4)).freeBerths(),
                    "replays must not consume more berths");
        }

        @Test
        @DisplayName("the same key with a different body is refused")
        void reusedKeyRefused() {
            holdSeats.handle(command("k1", 1));

            var different =
                    new HoldSeatsCommand(
                            pool,
                            SegmentRange.of(0, 2),
                            travellers(1),
                            7L,
                            "k1",
                            "a-different-hash",
                            NOW);

            assertInstanceOf(Result.IdempotencyKeyReused.class, holdSeats.handle(different));
        }

        @Test
        @DisplayName("a claim with no booking yet maps to RETRY_LATER")
        void pendingClaimRetriesLater() {
            idempotency.claimWithoutCompleting("k1", 7L, "hash-k1");

            assertInstanceOf(Result.RetryLater.class, holdSeats.handle(command("k1", 1)));
        }

        @Test
        @DisplayName("FR-19 makes the key mandatory")
        void keyIsRequired() {
            // Generating one server-side would defeat the purpose: the client's
            // retry would carry a different key and allocate a second set of
            // berths.
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new HoldSeatsCommand(
                                    pool,
                                    SegmentRange.of(0, 4),
                                    travellers(1),
                                    7L,
                                    "  ",
                                    "hash",
                                    NOW));
        }
    }

    // --------------------------------------------------------- rejections

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        @DisplayName("FR-29: a locked Tatkal pool reports when it opens")
        void tatkalLockedBeforeWindow() {
            var beforeWindow =
                    new HoldSeatsCommand(
                            pool,
                            SegmentRange.of(0, 4),
                            travellers(1),
                            7L,
                            "k1",
                            "hash",
                            TATKAL_OPEN.minusSeconds(1));

            var locked =
                    assertInstanceOf(Result.QuotaLocked.class, holdSeats.handle(beforeWindow));

            // Reporting the instant is the point. A client that knows when to come
            // back waits; one that does not polls, which is the herd FR-30 exists
            // to avoid.
            assertEquals(TATKAL_OPEN, locked.opensAt());
        }

        @Test
        @DisplayName("a locked pool consumes no idempotency key")
        void lockedPoolLeavesKeyUnclaimed() {
            var beforeWindow =
                    new HoldSeatsCommand(
                            pool, SegmentRange.of(0, 4), travellers(1), 7L, "k1", "hash",
                            TATKAL_OPEN.minusSeconds(1));
            holdSeats.handle(beforeWindow);

            // The cheap rejection happens before the claim, so retrying the same
            // key once the window opens wins it cleanly rather than colliding
            // with a key burnt on a request that could never have succeeded.
            assertEquals(Optional.empty(), idempotency.bookingIdFor("k1"));
            assertInstanceOf(Result.Held.class, holdSeats.handle(command("k1", 1)));
        }

        @Test
        @DisplayName("GENERAL pools are never window-locked")
        void generalPoolAlwaysOpen() {
            var general = new PoolKey(2L, TravelClass.AC3, QuotaType.GENERAL);
            allocator.provision(general, 2, 4);
            schedules.addPool(general, 2, 4, JOURNEY);

            var longBefore =
                    new HoldSeatsCommand(
                            pool, SegmentRange.of(0, 4), travellers(1), 7L, "k", "h",
                            TATKAL_OPEN.minusSeconds(86_400));

            assertInstanceOf(
                    Result.Held.class,
                    holdSeats.handle(
                            new HoldSeatsCommand(
                                    general, longBefore.range(), travellers(1), 7L, "kg", "hg",
                                    longBefore.now())));
        }

        @Test
        @DisplayName("FR-51: an exhausted pool is SEAT_UNAVAILABLE, not an error")
        void exhaustedPoolIsUnavailable() {
            holdSeats.handle(command("fills-it", 3));

            var result = holdSeats.handle(command("too-late", 1));
            var unavailable = assertInstanceOf(Result.SeatUnavailable.class, result);
            assertEquals(0, unavailable.available());
        }

        @Test
        @DisplayName("an unavailable hold releases its idempotency key for retry")
        void unavailableReleasesKey() {
            holdSeats.handle(command("fills-it", 3));
            holdSeats.handle(command("retryable", 1));

            // The rollback is what releases the claim, so a client may retry the
            // same key against a train that has since freed a berth.
            assertEquals(Optional.empty(), idempotency.bookingIdFor("retryable"));
            assertTrue(idempotency.rolledBackKeys().contains("retryable"));
        }

        @Test
        @DisplayName("FR-20: a caller over the hold cap is refused")
        void holdCapEnforced() {
            bookings.setActiveHolds(3);
            var result = holdSeats.handle(command("k1", 1));

            var tooMany = assertInstanceOf(Result.TooManyHolds.class, result);
            assertEquals(3, tooMany.limit());
        }

        @Test
        @DisplayName("a charted schedule is closed to booking")
        void chartedScheduleClosed() {
            schedules.setChartPrepared(pool, true);
            assertInstanceOf(Result.ChartPrepared.class, holdSeats.handle(command("k1", 1)));
        }

        @Test
        @DisplayName("a range past the end of the route is refused")
        void rangeBeyondRoute() {
            var tooLong =
                    new HoldSeatsCommand(
                            pool, SegmentRange.of(0, 8), travellers(1), 7L, "k1", "hash", NOW);
            assertInstanceOf(Result.InvalidRange.class, holdSeats.handle(tooLong));
        }

        @Test
        @DisplayName("an unknown pool is a 404, not a crash")
        void unknownPool() {
            var missing = new PoolKey(999L, TravelClass.SL, QuotaType.GENERAL);
            var result =
                    holdSeats.handle(
                            new HoldSeatsCommand(
                                    missing, SegmentRange.of(0, 2), travellers(1), 7L, "k", "h", NOW));
            assertInstanceOf(Result.UnknownPool.class, result);
        }
    }

    // ------------------------------------------------------------ compensation

    @Nested
    @DisplayName("the part that cannot be atomic")
    class Compensation {

        @Test
        @DisplayName("a failed booking insert releases the berths it had taken")
        void insertFailureReleasesTheHold() {
            // The berths live in Redis and the booking in Postgres; no
            // transaction spans both. Without compensation this leaves berths
            // held for a booking that does not exist, until the TTL expires.
            bookings.failNextInsert();

            int freeBefore = allocator.availability(pool, SegmentRange.of(0, 4)).freeBerths();

            assertThrows(RuntimeException.class, () -> holdSeats.handle(command("k1", 2)));

            assertEquals(
                    freeBefore,
                    allocator.availability(pool, SegmentRange.of(0, 4)).freeBerths(),
                    "the berths must come back immediately, not after the TTL");
        }
    }

    // ------------------------------------------------------------------ fakes

    /** Runs work directly; rollback is simulated by the store's own bookkeeping. */
    /**
     * Runs work inline, honouring the port's contract and <b>only</b> the port's
     * contract.
     *
     * <p>An earlier version decided for itself which return values meant rollback,
     * by inspecting their types. That made {@code unavailableReleasesKey} pass
     * against behaviour no production adapter had: {@link UnitOfWork} committed on
     * normal return, so SEAT_UNAVAILABLE would have burnt the key. A fake that
     * invents semantics tests the fake.
     */
    private final class DirectUnitOfWork implements UnitOfWork {
        @Override
        public <T> T inTransaction(Supplier<T> work, Predicate<T> rollbackIf) {
            idempotency.beginTransaction();
            try {
                T result = work.get();
                if (rollbackIf.test(result)) {
                    idempotency.rollback();
                } else {
                    idempotency.commit();
                }
                return result;
            } catch (RuntimeException e) {
                idempotency.rollback();
                throw e;
            }
        }
    }

    private static final class FakeIdempotencyStore implements IdempotencyStore {
        private final Map<String, String> hashes = new HashMap<>();
        private final Map<String, Long> bookingIds = new HashMap<>();
        private final List<String> claimedInTransaction = new ArrayList<>();
        private final List<String> rolledBack = new ArrayList<>();

        void beginTransaction() {
            claimedInTransaction.clear();
        }

        void commit() {
            claimedInTransaction.clear();
        }

        void rollback() {
            for (String key : claimedInTransaction) {
                hashes.remove(key);
                bookingIds.remove(key);
                rolledBack.add(key);
            }
            claimedInTransaction.clear();
        }

        List<String> rolledBackKeys() {
            return List.copyOf(rolledBack);
        }

        void claimWithoutCompleting(String key, long userId, String requestHash) {
            hashes.put(key, requestHash);
        }

        @Override
        public Claim claim(String key, long userId, String requestHash) {
            String existing = hashes.get(key);
            if (existing == null) {
                hashes.put(key, requestHash);
                claimedInTransaction.add(key);
                return new Claim.Won();
            }
            if (!existing.equals(requestHash)) {
                return new Claim.Reused(existing);
            }
            Long bookingId = bookingIds.get(key);
            return bookingId == null ? new Claim.Pending() : new Claim.Duplicate(bookingId);
        }

        @Override
        public void complete(String key, long bookingId) {
            bookingIds.put(key, bookingId);
        }

        @Override
        public Optional<Long> bookingIdFor(String key) {
            return Optional.ofNullable(bookingIds.get(key));
        }
    }

    private static final class FakeBookingRepository implements BookingRepository {
        private final Map<Long, BookingView> stored = new HashMap<>();
        private final AtomicLong nextId = new AtomicLong(1);
        private int activeHolds;
        private boolean failNext;
        private NewHeldBooking lastCreated;

        void setActiveHolds(int count) {
            this.activeHolds = count;
        }

        void failNextInsert() {
            this.failNext = true;
        }

        void setStatus(long bookingId, BookingStatus status) {
            var existing = stored.get(bookingId);
            stored.put(
                    bookingId,
                    new BookingView(
                            existing.id(), existing.pnr(), status, existing.pool(),
                            existing.range(), existing.passengerCount(), existing.farePaise(),
                            existing.userId(), existing.holdExpiresAt(), existing.berthIds()));
        }

        NewHeldBooking lastCreated() {
            return lastCreated;
        }

        @Override
        public long createHeld(NewHeldBooking booking) {
            lastCreated = booking;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated insert failure");
            }
            long id = nextId.getAndIncrement();
            stored.put(
                    id,
                    new BookingView(
                            id,
                            Optional.empty(),
                            BookingStatus.HELD,
                            booking.pool(),
                            booking.range(),
                            booking.passengerCount(),
                            booking.farePaise(),
                            booking.userId(),
                            Optional.of(booking.holdExpiresAt()),
                            booking.berthIds()));
            return id;
        }

        @Override
        public Optional<BookingView> findById(long bookingId) {
            return Optional.ofNullable(stored.get(bookingId));
        }

        @Override
        public int countActiveHolds(long userId, Instant now) {
            return activeHolds;
        }

        // The confirmation path (FR-24, FR-25). Unreachable from the hold path,
        // and left unimplemented rather than faked: a fake here would be dead
        // code that quietly starts being exercised if HoldSeats ever grows a
        // dependency on it, which is exactly the change that should be noticed.

        @Override
        public Optional<BookingView> findByIdForUpdate(long bookingId) {
            throw new UnsupportedOperationException("not part of the hold path");
        }


        // Cancellation (FR-43). Not reachable from this use case; unimplemented
        // rather than faked, so a dependency appearing here is noticed.

        @Override
        public Optional<BookingView> findByPnr(String pnr) {
            throw new UnsupportedOperationException("not part of this path");
        }

        @Override
        public Optional<String> holdIdOf(long bookingId) {
            throw new UnsupportedOperationException("not part of this path");
        }

        @Override
        public boolean cancel(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of this path");
        }

        @Override
        public boolean releaseHold(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of this path");
        }

        @Override
        public int deleteAllocations(long bookingId) {
            throw new UnsupportedOperationException("not part of this path");
        }

        @Override
        public boolean beginPayment(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of the hold path");
        }

        @Override
        public boolean markFailed(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of the hold path");
        }

        @Override
        public AllocationOutcome persistAllocations(
                long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds) {
            throw new UnsupportedOperationException("not part of the hold path");
        }

        @Override
        public boolean confirm(long bookingId, String pnr, Instant confirmedAt) {
            throw new UnsupportedOperationException("not part of the hold path");
        }

        @Override
        public boolean markFailedRefunded(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of the hold path");
        }
    }

    private static final class FakeScheduleQuery implements ScheduleQuery {
        private final Map<PoolKey, PoolDescriptor> pools = new HashMap<>();

        FakeScheduleQuery(PoolKey pool, int berths, int segments, LocalDate journeyDate) {
            addPool(pool, berths, segments, journeyDate);
        }

        void addPool(PoolKey pool, int berths, int segments, LocalDate journeyDate) {
            pools.put(
                    pool,
                    new PoolDescriptor(
                            pool,
                            berths,
                            segments,
                            journeyDate,
                            journeyDate.atTime(16, 55).toInstant(java.time.ZoneOffset.UTC),
                            false));
        }

        void setChartPrepared(PoolKey pool, boolean charted) {
            var existing = pools.get(pool);
            pools.put(
                    pool,
                    new PoolDescriptor(
                            existing.key(), existing.berthCount(), existing.segmentCount(),
                            existing.journeyDate(), existing.departureAt(), charted));
        }

        @Override
        public Optional<PoolDescriptor> findPool(PoolKey pool) {
            return Optional.ofNullable(pools.get(pool));
        }

        @Override
        public BigDecimal distanceKm(long scheduleId, SegmentRange range) {
            // §6.9's worked example: NDLS->RTM is 730 km.
            return new BigDecimal("730");
        }

        // The API boundary's two translations. Not part of HoldSeats' own
        // orchestration - the controller resolves station codes before building
        // the command - so unimplemented rather than faked.

        @Override
        public Optional<SegmentRange> resolveRange(
                long scheduleId, String fromStationCode, String toStationCode) {
            throw new UnsupportedOperationException("resolved at the API boundary");
        }

        @Override
        public List<BerthDetail> describeBerths(List<Long> berthIds) {
            throw new UnsupportedOperationException("resolved at the API boundary");
        }
    }
}
