package io.tatkalrush.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.TrainSearchQuery;
import io.tatkalrush.application.ports.TrainSearchQuery.PoolOnRoute;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code API-1}'s query, against real PostgreSQL.
 *
 * <p>The fixture is built around one thing that cannot be checked by reading:
 * <b>two trains serve NDLS→BCT with different routes</b>, so the same journey is
 * {@code [0,4)} on one and {@code [0,2)} on the other. A query that resolved the
 * range once and reused it would pass every test written against a single train
 * and be wrong for every real search.
 *
 * <p>The reverse working of the same route is seeded too. Both its stations are on
 * the route, so a join that checks membership without checking order returns it —
 * and its range would be negative, which surfaces as an exception from a search
 * rather than as an absent row.
 */
class JdbcTrainSearchQueryTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static final LocalDate OPEN_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate CHARTED_DATE = LocalDate.of(2026, 10, 2);
    private static final LocalDate DEPARTED_DATE = LocalDate.of(2026, 10, 3);
    private static final LocalDate CANCELLED_DATE = LocalDate.of(2026, 10, 4);

    private static Connection admin;
    private static TrainSearchQuery search;

    @BeforeAll
    static void startAndMigrate() throws SQLException {
        POSTGRES.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        DataSource dataSource = source;

        admin =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedNetwork();

        search = new JdbcTrainSearchQuery(dataSource);
    }

    @AfterAll
    static void stop() throws SQLException {
        if (admin != null) {
            admin.close();
        }
        POSTGRES.stop();
    }

    // ── FR-12 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("which trains serve the journey")
    class RouteMatching {

        @Test
        void bothTrainsServingTheCityPairAreFound() {
            List<String> trains = ndlsToBct().stream().map(PoolOnRoute::trainNumber).distinct().toList();

            assertEquals(List.of("12951", "12953"), trains);
        }

        @Test
        void theReverseWorkingIsNotAJourney() {
            // 12952 runs BCT -> NDLS and stops at both stations. Only the ordering
            // of their seq values distinguishes it, and without that filter it
            // would come back with a negative range.
            assertFalse(
                    ndlsToBct().stream().anyMatch(p -> p.trainNumber().equals("12952")),
                    "a passenger cannot travel NDLS to BCT on a train running BCT to NDLS");
        }

        @Test
        void itFindsTheReverseWorkingWhenAskedTheOtherWayRound() {
            var reverse =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query("BCT", "NDLS", OPEN_DATE, Optional.empty()));

            assertEquals(
                    List.of("12952"),
                    reverse.stream().map(PoolOnRoute::trainNumber).distinct().toList());
        }

        @Test
        void aTrainThatDoesNotReachTheDestinationIsNotOffered() {
            // 19015 runs NDLS -> ADI. The origin matches and the destination does
            // not, which an inner join on both stops already excludes - asserted
            // because an OUTER join here would silently offer it.
            assertFalse(ndlsToBct().stream().anyMatch(p -> p.trainNumber().equals("19015")));
        }

        @Test
        void anIntermediatePairFindsOnlyTheTrainThatStopsAtBoth() {
            var pools =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query("MTJ", "RTM", OPEN_DATE, Optional.empty()));

            // 12953 runs NDLS-KOTA-BCT and stops at neither.
            assertEquals(
                    List.of("12951"),
                    pools.stream().map(PoolOnRoute::trainNumber).distinct().toList());
            assertEquals(1, pools.get(0).range().fromSeq());
            assertEquals(3, pools.get(0).range().toSeq());
        }
    }

    // ── the numbers ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("what each row carries")
    class RowContents {

        @Test
        void theRangeIsPerTrainForTheSameCityPair() {
            assertEquals(4, first("12951").range().toSeq(), "NDLS-MTJ-KOTA-RTM-BCT");
            assertEquals(2, first("12953").range().toSeq(), "NDLS-KOTA-BCT");
            assertEquals(0, first("12951").range().fromSeq());
            assertEquals(0, first("12953").range().fromSeq());
        }

        @Test
        void segmentCountIsMaxSeqAndDiffersPerTrain() {
            // Five stops, seq 0..4, so max(seq) is 4 and there are four segments.
            // count(*) would give five here and admit a booking on a leg the train
            // does not run - which SegmentMask sets without complaint.
            assertEquals(4, first("12951").segmentCount());
            assertEquals(2, first("12953").segmentCount());
        }

        @Test
        void distanceIsTheSubtractionOfTwoCumulativeReadings() {
            var pools =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query("MTJ", "RTM", OPEN_DATE, Optional.empty()));

            // 610.75 - 180.50, exactly. FR-67 takes a ceil of this and INV-7
            // recomputes it independently, so a double anywhere on this path makes
            // that comparison fail intermittently on values that look exact.
            assertEquals(0, new BigDecimal("430.25").compareTo(pools.get(0).distanceKm()));
        }

        @Test
        void timesComeFromTheRequestedStopsNotTheTrainsOwnEnds() {
            var pools =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query("MTJ", "RTM", OPEN_DATE, Optional.empty()));

            assertEquals(LocalTime.of(19, 20), pools.get(0).departsAt(), "departure from MTJ");
            assertEquals(LocalTime.of(2, 45), pools.get(0).arrivesAt(), "arrival at RTM");
        }

        @Test
        void aTrainsOwnOriginHasNoArrivalTimeAndThatStaysNull() {
            // NDLS is 12951's origin: arr_time is NULL there, and dep_time is NULL
            // at BCT. Read as objects rather than as LocalTime primitives, or a
            // missing time becomes midnight and the response claims a departure.
            assertEquals(LocalTime.of(16, 55), first("12951").departsAt());
            assertEquals(LocalTime.of(8, 35), first("12951").arrivesAt());
        }

        @Test
        void hotTrainsAreFlagged() {
            assertTrue(first("12951").hot(), "FR-49: P3 targets one of these");
            assertFalse(first("12953").hot());
        }
    }

    // ── pools ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("one row per pool, not per train")
    class Pools {

        @Test
        void everyClassAndQuotaOnTheScheduleIsARow() {
            List<PoolOnRoute> pools =
                    ndlsToBct().stream().filter(p -> p.trainNumber().equals("12951")).toList();

            assertEquals(3, pools.size(), "SL GENERAL, SL TATKAL, 3A GENERAL");
            assertEquals(
                    List.of(
                            TravelClass.AC3 + "/" + QuotaType.GENERAL,
                            TravelClass.SL + "/" + QuotaType.GENERAL,
                            TravelClass.SL + "/" + QuotaType.TATKAL),
                    pools.stream()
                            .map(p -> p.pool().travelClass() + "/" + p.pool().quotaType())
                            .toList(),
                    "ordered by class then quota, so two benchmark runs diff cleanly");
        }

        @Test
        void generalAndTatkalCarryTheirOwnBerthCounts() {
            var pools =
                    ndlsToBct().stream()
                            .filter(p -> p.trainNumber().equals("12951"))
                            .filter(p -> p.pool().travelClass() == TravelClass.SL)
                            .toList();

            assertEquals(72, pools.get(0).totalBerths(), "GENERAL");
            assertEquals(8, pools.get(1).totalBerths(), "TATKAL, FR-9's ceil(0.10 x 72)");
        }

        @Test
        void theClassFilterRestrictsTheRowsInSql() {
            var pools =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query(
                                    "NDLS", "BCT", OPEN_DATE, Optional.of(TravelClass.AC3)));

            assertEquals(1, pools.size());
            assertEquals(TravelClass.AC3, pools.get(0).pool().travelClass());
        }

        @Test
        void anAbsentFilterMeansEveryClass() {
            assertEquals(4, ndlsToBct().size(), "three pools on 12951, one on 12953");
        }
    }

    // ── schedule status ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("which schedules count as answers")
    class ScheduleStatus {

        @Test
        void aChartedScheduleIsStillOfferedAndFlagged() {
            var pools =
                    search.poolsOnRoute(
                            new TrainSearchQuery.Query(
                                    "NDLS", "BCT", CHARTED_DATE, Optional.empty()));

            assertFalse(pools.isEmpty(), "a charted train still runs and still exists");
            assertTrue(
                    pools.stream().allMatch(PoolOnRoute::chartPrepared),
                    "FR-42 closed booking; saying so beats saying the train does not run");
        }

        @Test
        void aDepartedScheduleIsNotAnAnswer() {
            assertTrue(
                    search.poolsOnRoute(
                                    new TrainSearchQuery.Query(
                                            "NDLS", "BCT", DEPARTED_DATE, Optional.empty()))
                            .isEmpty());
        }

        @Test
        void aCancelledScheduleIsNotAnAnswer() {
            assertTrue(
                    search.poolsOnRoute(
                                    new TrainSearchQuery.Query(
                                            "NDLS", "BCT", CANCELLED_DATE, Optional.empty()))
                            .isEmpty());
        }

        @Test
        void aDateWithNoScheduleIsEmptyRatherThanAnError() {
            assertTrue(
                    search.poolsOnRoute(
                                    new TrainSearchQuery.Query(
                                            "NDLS",
                                            "BCT",
                                            LocalDate.of(2027, 1, 1),
                                            Optional.empty()))
                            .isEmpty());
        }
    }

    // ── the diagnostic ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("telling a typo from an empty route")
    class StationExistence {

        @Test
        void onlyKnownCodesComeBack() {
            assertEquals(List.of("NDLS"), search.stationsExist(List.of("NDLS", "XXXX")));
        }

        @Test
        void bothKnownCodesComeBack() {
            assertEquals(2, search.stationsExist(List.of("NDLS", "BCT")).size());
        }

        @Test
        void anEmptyListIsNotAQuery() {
            // An IN () clause is a syntax error in Postgres, so the guard is load
            // bearing rather than a micro-optimisation.
            assertTrue(search.stationsExist(List.of()).isEmpty());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<PoolOnRoute> ndlsToBct() {
        return search.poolsOnRoute(
                new TrainSearchQuery.Query("NDLS", "BCT", OPEN_DATE, Optional.empty()));
    }

    private static PoolOnRoute first(String trainNumber) {
        return ndlsToBct().stream()
                .filter(p -> p.trainNumber().equals(trainNumber))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rows for train " + trainNumber));
    }

    private static void seedNetwork() throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute(
                    "INSERT INTO stations (code, name) VALUES"
                        + " ('NDLS','New Delhi'),('MTJ','Mathura'),('KOTA','Kota'),"
                        + " ('RTM','Ratlam'),('BCT','Mumbai Central'),('ADI','Ahmedabad')");

            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id, is_hot)"
                        + " VALUES ('12951','Mumbai Rajdhani',1,5,true),"
                        + " ('12953','August Kranti',1,5,false),"
                        + " ('12952','Mumbai Rajdhani (up)',5,1,false),"
                        + " ('19015','Dehradun Express',1,6,false)");

            // 12951: five stops, four segments. Cumulative distances, deliberately
            // not round: the .25/.75 values are what drift if this path ever
            // touches a double.
            st.execute(
                    "INSERT INTO train_stops"
                        + " (train_id, station_id, seq, arr_time, dep_time, distance_km) VALUES"
                        + " (1,1,0,NULL,'16:55',0.00),"
                        + " (1,2,1,'19:15','19:20',180.50),"
                        + " (1,3,2,'22:40','22:45',410.25),"
                        + " (1,4,3,'02:45','02:50',610.75),"
                        + " (1,5,4,'08:35',NULL,730.00)");

            // 12953: the SAME city pair over a different route. Three stops, two
            // segments - so NDLS->BCT is [0,4) on 12951 and [0,2) here.
            st.execute(
                    "INSERT INTO train_stops"
                        + " (train_id, station_id, seq, arr_time, dep_time, distance_km) VALUES"
                        + " (2,1,0,NULL,'17:40',0.00),"
                        + " (2,3,1,'23:30','23:35',400.00),"
                        + " (2,5,2,'09:55',NULL,700.00)");

            // 12952: the reverse working. Both NDLS and BCT are on it.
            st.execute(
                    "INSERT INTO train_stops"
                        + " (train_id, station_id, seq, arr_time, dep_time, distance_km) VALUES"
                        + " (3,5,0,NULL,'17:00',0.00),"
                        + " (3,4,1,'22:30','22:35',119.25),"
                        + " (3,3,2,'02:30','02:35',319.75),"
                        + " (3,2,3,'06:00','06:05',549.50),"
                        + " (3,1,4,'08:35',NULL,730.00)");

            st.execute(
                    "INSERT INTO train_stops"
                        + " (train_id, station_id, seq, arr_time, dep_time, distance_km) VALUES"
                        + " (4,1,0,NULL,'21:10',0.00),"
                        + " (4,6,1,'09:20',NULL,934.00)");

            st.execute(
                    "INSERT INTO coaches (train_id, code, travel_class, berth_count) VALUES"
                        + " (1,'S1','SL',72),(1,'B1','3A',64),(2,'S1','SL',72),"
                        + " (3,'S1','SL',72),(4,'S1','SL',72)");

            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at,"
                        + " chart_prepared_at) VALUES"
                        + " (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30',NULL),"
                        + " (2,'2026-10-01','OPEN','2026-10-01 17:40+05:30',NULL),"
                        + " (3,'2026-10-01','OPEN','2026-10-01 17:00+05:30',NULL),"
                        + " (4,'2026-10-01','OPEN','2026-10-01 21:10+05:30',NULL),"
                        + " (1,'2026-10-02','CHARTED','2026-10-02 16:55+05:30',"
                        + "     '2026-10-02 12:55+05:30'),"
                        + " (1,'2026-10-03','DEPARTED','2026-10-03 16:55+05:30',NULL),"
                        + " (1,'2026-10-04','CANCELLED','2026-10-04 16:55+05:30',NULL)");

            st.execute(
                    "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                        + " VALUES (1,'SL','GENERAL',72),(1,'SL','TATKAL',8),(1,'3A','GENERAL',64),"
                        + " (2,'SL','GENERAL',72),(3,'SL','GENERAL',72),(4,'SL','GENERAL',72),"
                        + " (5,'SL','GENERAL',72),(6,'SL','GENERAL',72),(7,'SL','GENERAL',72)");
        }
    }
}
