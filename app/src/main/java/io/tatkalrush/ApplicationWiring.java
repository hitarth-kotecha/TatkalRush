package io.tatkalrush;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
import io.tatkalrush.adapters.paymentsim.HttpPaymentGateway;
import io.tatkalrush.adapters.paymentsim.WebhookSigner;
import io.tatkalrush.adapters.persistence.JdbcBookingRepository;
import io.tatkalrush.adapters.persistence.JdbcIdempotencyStore;
import io.tatkalrush.adapters.persistence.JdbcPaymentRepository;
import io.tatkalrush.adapters.persistence.JdbcPnrSequence;
import io.tatkalrush.adapters.persistence.JdbcScheduleQuery;
import io.tatkalrush.adapters.persistence.SpringUnitOfWork;
import io.tatkalrush.adapters.web.PaymentWebhookController;
import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IdempotencyStore;
import io.tatkalrush.application.ports.IntegrityAlarm;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentReferences;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PnrSequence;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.application.usecases.CancelBooking;
import io.tatkalrush.application.usecases.ConfirmBooking;
import io.tatkalrush.application.usecases.HoldSeats;
import io.tatkalrush.application.usecases.InitiatePayment;
import io.tatkalrush.application.usecases.SettlePayment;
import java.time.Duration;
import java.time.InstantSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The composition root's wiring.
 *
 * <p>Every port in {@code application/} is framework-free precisely <b>because</b>
 * this file exists. The use cases below take constructor arguments and know nothing
 * about Spring; the knowledge that {@code BookingRepository} is JDBC, that the
 * allocator is Redis, and that the PSP is reachable over HTTP lives here and
 * nowhere else. That is what makes §9.4's strategy swap a change to one bean rather
 * than a change to the booking logic.
 *
 * <p>Excluded from the {@code psp-sim} profile. §8.3 runs one image in three roles,
 * and the simulator needs neither a connection pool nor a Redis client — inside its
 * 256 MB limit, holding both would be a measurable cost for something it never
 * calls.
 */
@Configuration
@Profile("!psp-sim")
public class ApplicationWiring {

    private static final Logger log = LoggerFactory.getLogger(ApplicationWiring.class);

    // ── infrastructure ──────────────────────────────────────────────────────

    /**
     * A port for the clock, so time is an argument rather than an ambient fact.
     *
     * <p>FR-28's Tatkal window, FR-17's hold TTL and FR-25's expiry check are all
     * decisions about "now", and a use case that calls {@code Instant.now()}
     * directly cannot be tested at a boundary without waiting for one.
     */
    @Bean
    InstantSource clock() {
        return InstantSource.system();
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create(RedisURI.create(host, port));
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }

    @Bean
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> connection) {
        return connection.sync();
    }

    // ── ports ───────────────────────────────────────────────────────────────

    @Bean
    UnitOfWork unitOfWork(PlatformTransactionManager transactionManager) {
        return new SpringUnitOfWork(transactionManager);
    }

    @Bean
    BookingRepository bookingRepository(DataSource dataSource) {
        return new JdbcBookingRepository(dataSource);
    }

    @Bean
    PaymentRepository paymentRepository(DataSource dataSource) {
        return new JdbcPaymentRepository(dataSource);
    }

    @Bean
    ScheduleQuery scheduleQuery(DataSource dataSource) {
        return new JdbcScheduleQuery(dataSource);
    }

    @Bean
    IdempotencyStore idempotencyStore(DataSource dataSource) {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Bean
    PnrSequence pnrSequence(DataSource dataSource) {
        return new JdbcPnrSequence(dataSource);
    }

    /**
     * §9.4's swap point. Strategy A today; Strategy B replaces this one bean and
     * nothing above it changes, which is what makes the comparison controlled
     * rather than a rewrite with a different name.
     */
    @Bean
    SeatAllocator seatAllocator(RedisCommands<String, String> redis) {
        return new RedisSeatAllocator(redis);
    }

    @Bean
    PaymentReferences paymentReferences() {
        // A UUID, not a sequence. FR-26's argument against random generation is
        // about PNRs, which must be short, human-readable and collision-free in a
        // ten-digit space. A payment reference is opaque and its uniqueness is
        // enforced by payments.psp_payment_id being UNIQUE, so the database is the
        // guarantee and the generator only has to avoid being silly.
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    PaymentGateway paymentGateway(
            @Value("${tatkalrush.psp.base-url:http://psp-sim:8080}") String baseUrl,
            @Value("${tatkalrush.psp.timeout-ms:2000}") long timeoutMs) {
        return new HttpPaymentGateway(baseUrl, Duration.ofMillis(timeoutMs));
    }

    @Bean
    IntegrityAlarm integrityAlarm(MeterRegistry meters) {
        Counter counter =
                Counter.builder("allocation_constraint_violations_total")
                        .description(
                                "INV-11/NFR-9: the exclusion constraint rejected an insert against"
                                    + " a LIVE hold. Any non-zero value fails the run.")
                        .register(meters);

        return (bookingId, berthId) -> {
            counter.increment();
            // ERROR, and deliberately so. Almost nothing else in this system logs
            // at ERROR, which makes this line findable in a 30-minute soak's logs
            // without knowing what to grep for.
            log.error(
                    "ALLOCATION CONSTRAINT VIOLATED (INV-11): booking {} berth {}."
                        + " An allocator sold one berth twice and the customer has already"
                        + " paid. This run has failed.",
                    bookingId,
                    berthId);
        };
    }

    @Bean
    PaymentWebhookController.WebhookVerifier webhookVerifier(
            @Value("${tatkalrush.psp.secret:tatkal-rush-dev-secret}") String secret) {
        // The same secret the simulator signs with. Shared configuration rather
        // than a shared object: the two run in different processes, and a test
        // that wires one signer into both would prove they agree with themselves.
        WebhookSigner signer = new WebhookSigner(secret);
        return signer::verify;
    }

    // ── use cases ───────────────────────────────────────────────────────────

    @Bean
    HoldSeats holdSeats(
            SeatAllocator allocator,
            IdempotencyStore idempotency,
            BookingRepository bookings,
            ScheduleQuery schedules,
            UnitOfWork unitOfWork,
            @Value("${tatkalrush.hold.ttl-ms:120000}") long holdTtlMillis,
            @Value("${tatkalrush.hold.max-active:3}") int maxActiveHolds) {
        // FR-17's 120 s and FR-20's 3, as configuration rather than constants -
        // chaos scenarios need to move them, and a benchmark report has to record
        // what they were.
        return new HoldSeats(
                allocator, idempotency, bookings, schedules, unitOfWork,
                holdTtlMillis, maxActiveHolds);
    }

    @Bean
    ConfirmBooking confirmBooking(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            PnrSequence pnrSequence,
            IntegrityAlarm alarm,
            UnitOfWork unitOfWork) {
        return new ConfirmBooking(bookings, payments, gateway, pnrSequence, alarm, unitOfWork);
    }

    @Bean
    InitiatePayment initiatePayment(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentReferences references,
            UnitOfWork unitOfWork) {
        return new InitiatePayment(bookings, payments, gateway, references, unitOfWork);
    }

    @Bean
    CancelBooking cancelBooking(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            SeatAllocator allocator,
            ScheduleQuery schedules,
            UnitOfWork unitOfWork) {
        return new CancelBooking(bookings, payments, gateway, allocator, schedules, unitOfWork);
    }

    @Bean
    SettlePayment settlePayment(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            ConfirmBooking confirmBooking,
            UnitOfWork unitOfWork) {
        return new SettlePayment(bookings, payments, gateway, confirmBooking, unitOfWork);
    }
}
