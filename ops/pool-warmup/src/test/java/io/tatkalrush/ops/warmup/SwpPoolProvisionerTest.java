package io.tatkalrush.ops.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.adapters.allocatorswp.JdbcCheckpointStore;
import io.tatkalrush.adapters.allocatorswp.JdbcHoldRoutingStore;
import io.tatkalrush.adapters.allocatorswp.KafkaSeatAllocator;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.domain.booking.Pnr;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link SwpPoolProvisioner} against real Postgres and real Kafka.
 *
 * <p>Deliberately does not re-test shape warnings, ordinal ordering or
 * departed-schedule exclusion — those are {@link PoolShapeReader}'s behaviour,
 * already covered by {@code PoolRebuilderTest}'s 18 cases against the exact
 * same shared code. What is specific to Strategy B, and tested here, is: the
 * pool actually becomes allocatable through a real {@link KafkaSeatAllocator},
 * and confirmed occupancy survives being provisioned into rather than being
 * wiped.
 */
class SwpPoolProvisionerTest {

    private static final String COMMANDS = "swp-warmup-commands";
    private static final String EVENTS = "swp-warmup-events";
    private static final String REPLIES = "swp-warmup-replies";

    /** Schedule 1, 4 segments, 2 SL berths. */
    private static final PoolKey SL_GENERAL = new PoolKey(1L, TravelClass.SL, QuotaType.GENERAL);

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    private static Connection conn;
    private static KafkaSeatAllocator allocator;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        KAFKA.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        conn =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedNetwork();

        var adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(COMMANDS, 1, (short) 1),
                                    new NewTopic(EVENTS, 1, (short) 1),
                                    new NewTopic(REPLIES, 1, (short) 1)))
                    .all()
                    .get();
        }

        DataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        allocator =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new JdbcCheckpointStore(dataSource), new JdbcHoldRoutingStore(dataSource));
        allocator.start();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (allocator != null) {
            allocator.close();
        }
        if (conn != null) {
            conn.close();
        }
        KAFKA.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetBookings() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "TRUNCATE ledger_entries, refunds, payment_events, payments,"
                        + " seat_allocations, passengers, bookings RESTART IDENTITY CASCADE");
        }
    }

    @Test
    @DisplayName("a freshly seeded pool becomes allocatable through a real KafkaSeatAllocator")
    void freshPoolBecomesAllocatable() throws SQLException {
        var result = new SwpPoolProvisioner(conn, allocator).provisionAll();

        assertEquals(1, result.pools());
        assertEquals(0, result.allocationsReplayed(), "nothing is booked yet");
        assertTrue(result.shapeWarnings().isEmpty(), result.shapeWarnings().toString());

        // The actual symptom this tool exists to remove: before provisioning,
        // this throws "pool not provisioned on this owner".
        var allocated =
                assertInstanceOf(
                        AllocationResult.Allocated.class,
                        allocator.allocate(
                                new AllocationRequest(
                                        SL_GENERAL,
                                        SegmentRange.of(0, 4),
                                        2,
                                        "warm-1",
                                        Instant.parse("2026-09-20T00:00:00Z"),
                                        120_000L)));
        assertEquals(2, allocated.berthCount());
    }

    @Test
    @DisplayName("a confirmed allocation is provisioned as already-occupied, not free")
    void confirmedAllocationStartsOccupied() throws SQLException {
        // Berth ordinal 1 (berth id 2) booked over [1,3).
        confirmBooking(1, 3, 2L);

        var result = new SwpPoolProvisioner(conn, allocator).provisionAll();
        assertEquals(1, result.allocationsReplayed());

        // Only one of the two berths is free on segment 1.
        assertEquals(1, allocator.availability(SL_GENERAL, SegmentRange.of(1, 2)).freeBerths());

        // The occupied berth cannot be allocated a second time - if provisioning
        // had wiped the confirmed occupancy, this would succeed and double-sell it.
        var second =
                allocator.allocate(
                        new AllocationRequest(
                                SL_GENERAL, SegmentRange.of(0, 4), 2, "attempt-2",
                                Instant.parse("2026-09-20T00:00:00Z"), 120_000L));
        assertInstanceOf(AllocationResult.Unavailable.class, second);
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static void confirmBooking(int fromSeq, int toSeq, long berthId) throws SQLException {
        String pnr = Pnr.fromSequence(1).value();
        try (Statement st = conn.createStatement()) {
            st.execute(
                    ("INSERT INTO bookings (pnr, schedule_id, travel_class, quota_type, from_seq,"
                        + " to_seq, status, booking_class, passenger_count, fare_paise, user_id,"
                        + " confirmed_at) VALUES ('%s', 1, 'SL', 'GENERAL', %d, %d,"
                        + " 'CONFIRMED', 'CNF', 1, 100000, 1, now())")
                            .formatted(pnr, fromSeq, toSeq));
            st.execute(
                    ("INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                        + " SELECT 1, %d, max(id), int4range(%d, %d) FROM bookings")
                            .formatted(berthId, fromSeq, toSeq));
            st.execute(
                    ("INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status,"
                        + " settled_at) SELECT max(id), 'psp-%s', 100000, 'SUCCESS', now()"
                        + " FROM bookings")
                            .formatted(pnr));
        }
    }

    /** One train, one OPEN schedule, one SL pool with 2 berths over 4 segments. */
    private static void seedNetwork() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "INSERT INTO stations (code, name) VALUES"
                        + " ('NDLS','New Delhi'),('MTJ','Mathura'),('KOTA','Kota'),"
                        + " ('RTM','Ratlam'),('BCT','Mumbai Central')");
            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id, is_hot)"
                        + " VALUES ('12951','Rajdhani',1,5,true)");
            st.execute(
                    "INSERT INTO train_stops (train_id, station_id, seq, distance_km) VALUES"
                        + " (1,1,0,0.00),(1,2,1,180.50),(1,3,2,410.25),"
                        + " (1,4,3,610.75),(1,5,4,730.00)");
            st.execute("INSERT INTO coaches (train_id, code, travel_class, berth_count) VALUES (1,'S1','SL',2)");
            st.execute("INSERT INTO berths (coach_id, ordinal, berth_type) VALUES (1,0,'LOWER'),(1,1,'LOWER')");
            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at,"
                        + " chart_prepared_at) VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30',NULL)");
            st.execute(
                    "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                        + " VALUES (1,'SL','GENERAL',2)");
            st.execute(
                    "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                        + " SELECT q.id, b.id, b.ordinal FROM quota_pools q"
                        + " JOIN berths b ON b.coach_id = 1"
                        + " WHERE q.travel_class = 'SL' AND q.quota_type = 'GENERAL'");
            st.execute("INSERT INTO users (external_ref) VALUES ('u1')");
        }
    }
}
