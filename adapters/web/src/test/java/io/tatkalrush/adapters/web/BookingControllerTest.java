package io.tatkalrush.adapters.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.tatkalrush.adapters.web.auth.JwtAuthFilter;
import io.tatkalrush.adapters.web.auth.StubJwt;
import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.usecases.CancelBooking;
import io.tatkalrush.application.usecases.HoldSeats;
import io.tatkalrush.application.usecases.InitiatePayment;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code API-4} and {@code API-5} at the HTTP boundary.
 *
 * <p>The one worth reading is {@code theUserIdComesFromTheTokenNotTheBody}. FR-59
 * says the id is "never accepted from the request body", and the enforcement is
 * that {@code HoldRequest} has no field for it — so the test sends one anyway and
 * asserts it was ignored.
 */
class BookingControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final long TOKEN_USER = 4_242L;

    private HoldSeats holdSeats;
    private InitiatePayment initiatePayment;
    private ScheduleQuery schedules;
    private CancelBooking cancelBooking;
    private BookingRepository bookingRepository;
    private StubJwt jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        holdSeats = mock(HoldSeats.class);
        initiatePayment = mock(InitiatePayment.class);
        schedules = mock(ScheduleQuery.class);
        jwt = new StubJwt("a-test-secret", Duration.ofHours(1), InstantSource.fixed(NOW));

        when(schedules.resolveRange(anyScheduleId(), any(), any()))
                .thenReturn(Optional.of(new SegmentRange(0, 4)));
        when(schedules.describeBerths(any()))
                .thenReturn(List.of(new ScheduleQuery.BerthDetail(7L, "S1", 41, "LOWER")));

        cancelBooking = mock(CancelBooking.class);
        bookingRepository = mock(BookingRepository.class);

        var controller =
                new BookingController(
                        holdSeats,
                        initiatePayment,
                        cancelBooking,
                        bookingRepository,
                        schedules,
                        InstantSource.fixed(NOW));

        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        // The real filter, so FR-59 is exercised rather than
                        // simulated by binding the ScopedValue directly.
                        .addFilters(new JwtAuthFilter(jwt))
                        .build();
    }

    private static long anyScheduleId() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-59: the caller's identity")
    class Identity {

        @Test
        void aRequestWithoutATokenIsRejected() throws Exception {
            mvc.perform(
                            post("/api/v1/bookings/hold")
                                    .header("Idempotency-Key", "k1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(holdBody()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            verify(holdSeats, never()).handle(any());
        }

        @Test
        void aForgedTokenIsRejected() throws Exception {
            mvc.perform(
                            post("/api/v1/bookings/hold")
                                    .header("Authorization", "Bearer not.a.token")
                                    .header("Idempotency-Key", "k1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(holdBody()))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * The body carries a {@code userId} and it is ignored — because
         * {@code HoldRequest} has no such component, so Jackson has nowhere to put
         * it. FR-59's requirement satisfied by absence rather than by a rule.
         */
        @Test
        void theUserIdComesFromTheTokenNotTheBody() throws Exception {
            givenHeld();

            String bodyClaimingAnotherUser =
                    """
                    {"scheduleId":1,"travelClass":"3A","quotaType":"TATKAL",
                     "fromStationCode":"NDLS","toStationCode":"BCT","userId":999999,
                     "passengers":[{"name":"A","age":30,"gender":"M"}]}
                    """;

            authenticatedHold("k1", bodyClaimingAnotherUser).andExpect(status().isCreated());

            var command = ArgumentCaptor.forClass(HoldSeats.HoldSeatsCommand.class);
            verify(holdSeats).handle(command.capture());

            assertEquals(
                    TOKEN_USER,
                    command.getValue().userId(),
                    "the body asked for 999999 and must have been ignored");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-19: the idempotency key and its fingerprint")
    class Idempotency {

        @Test
        void theHeaderIsRequired() throws Exception {
            mvc.perform(
                            post("/api/v1/bookings/hold")
                                    .header("Authorization", bearer())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(holdBody()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("FR-19")));

            verify(holdSeats, never()).handle(any());
        }

        /**
         * The hash covers meaning, not bytes — the opposite of the webhook
         * signature. A client that reformats its JSON between attempts is sending
         * the same request, and refusing it with IDEMPOTENCY_KEY_REUSED would
         * break a legitimate retry.
         */
        @Test
        void reformattingTheJsonDoesNotChangeTheFingerprint() throws Exception {
            givenHeld();

            String compact =
                    "{\"scheduleId\":1,\"travelClass\":\"3A\",\"quotaType\":\"TATKAL\","
                        + "\"fromStationCode\":\"NDLS\",\"toStationCode\":\"BCT\","
                        + "\"passengers\":[{\"name\":\"A\",\"age\":30,\"gender\":\"M\"}]}";

            String spacedAndReordered =
                    """
                    {
                       "travelClass" : "3A",
                       "quotaType"   : "TATKAL",
                       "scheduleId"  : 1,
                       "toStationCode"   : "BCT",
                       "fromStationCode" : "NDLS",
                       "passengers" : [ { "gender" : "M", "age" : 30, "name" : "A" } ]
                    }
                    """;

            authenticatedHold("k1", compact);
            authenticatedHold("k1", spacedAndReordered);

            var commands = ArgumentCaptor.forClass(HoldSeats.HoldSeatsCommand.class);
            verify(holdSeats, org.mockito.Mockito.times(2)).handle(commands.capture());

            assertEquals(
                    commands.getAllValues().get(0).requestHash(),
                    commands.getAllValues().get(1).requestHash(),
                    "whitespace and key order are not part of what a request means");
        }

        @Test
        void aDifferentPassengerIsADifferentRequest() throws Exception {
            givenHeld();

            authenticatedHold("k1", holdBody());
            authenticatedHold(
                    "k1",
                    """
                    {"scheduleId":1,"travelClass":"3A","quotaType":"TATKAL",
                     "fromStationCode":"NDLS","toStationCode":"BCT",
                     "passengers":[{"name":"SOMEONE ELSE","age":30,"gender":"M"}]}
                    """);

            var commands = ArgumentCaptor.forClass(HoldSeats.HoldSeatsCommand.class);
            verify(holdSeats, org.mockito.Mockito.times(2)).handle(commands.capture());

            assertNotEquals(
                    commands.getAllValues().get(0).requestHash(),
                    commands.getAllValues().get(1).requestHash(),
                    "answering this with the first booking would confirm the wrong person");
        }

        /** §11.1: a replay returns the CURRENT representation, never a stored copy. */
        @Test
        void aReplayReportsTheBookingsCurrentState() throws Exception {
            when(holdSeats.handle(any()))
                    .thenReturn(
                            new HoldSeats.Result.DuplicateRequest(
                                    new BookingRepository.BookingView(
                                            88L,
                                            Optional.empty(),
                                            BookingStatus.EXPIRED,
                                            pool(),
                                            new SegmentRange(0, 4),
                                            1,
                                            245_000L,
                                            TOKEN_USER,
                                            Optional.of(NOW.minusSeconds(1)),
                                            List.of(7L))));

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPIRED"))
                    .andExpect(jsonPath("$.bookingId").value(88));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§11.2: every outcome maps to a code")
    class ErrorMapping {

        @Test
        void aHoldIs201WithItsAllocations() throws Exception {
            givenHeld();

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("HELD"))
                    .andExpect(jsonPath("$.farePaise").value(245_000))
                    .andExpect(jsonPath("$.allocations[0].coach").value("S1"))
                    .andExpect(jsonPath("$.allocations[0].berth").value(41))
                    .andExpect(jsonPath("$.allocations[0].berthType").value("LOWER"));
        }

        /** FR-51: a 409, and deliberately not counted as an error. */
        @Test
        void seatUnavailableIsA409CarryingItsCode() throws Exception {
            when(holdSeats.handle(any())).thenReturn(new HoldSeats.Result.SeatUnavailable(0, 2));

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

            assertEquals(
                    true,
                    ApiError.SEAT_UNAVAILABLE.isCorrectOutcome(),
                    "NFR-7's budget must exclude this, so the mapping has to say so");
        }

        /** FR-29: the opening instant, not merely the fact that it is shut. */
        @Test
        void quotaLockedCarriesTheOpeningInstant() throws Exception {
            Instant opensAt = NOW.plusSeconds(3_600);
            when(holdSeats.handle(any())).thenReturn(new HoldSeats.Result.QuotaLocked(opensAt));

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("QUOTA_LOCKED"))
                    .andExpect(jsonPath("$.opensAt").value(opensAt.toString()));
        }

        @Test
        void tooManyHoldsIs429() throws Exception {
            when(holdSeats.handle(any())).thenReturn(new HoldSeats.Result.TooManyHolds(3, 3));

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("TOO_MANY_HOLDS"));
        }

        @Test
        void anUnroutableJourneyIsRejectedBeforeTheUseCaseRuns() throws Exception {
            when(schedules.resolveRange(anyScheduleId(), any(), any())).thenReturn(Optional.empty());

            authenticatedHold("k1", holdBody())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            verify(holdSeats, never()).handle(any());
        }

        @Test
        void everyProblemBodyIsProblemJson() throws Exception {
            when(holdSeats.handle(any())).thenReturn(new HoldSeats.Result.SeatUnavailable(0, 1));

            authenticatedHold("k1", holdBody())
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .content()
                                    .contentTypeCompatibleWith("application/problem+json"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("API-5")
    class Paying {

        @Test
        void anInitiatedPaymentIs202() throws Exception {
            when(initiatePayment.initiate(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new InitiatePayment.Result.Initiated(88L, 100L, "ref-1", 245_000L));

            mvc.perform(post("/api/v1/bookings/88/pay").header("Authorization", bearer()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                    .andExpect(jsonPath("$.paymentReference").value("ref-1"));
        }

        /**
         * An unreachable gateway is 202, not 5xx. The charge may have landed, so
         * the client must poll rather than retry with a fresh reference.
         */
        @Test
        void anUnknownOutcomeIs202AndSaysToPoll() throws Exception {
            when(initiatePayment.initiate(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(
                            new InitiatePayment.Result.OutcomeUnknown(88L, "ref-1", "read timeout"));

            mvc.perform(post("/api/v1/bookings/88/pay").header("Authorization", bearer()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("poll")));
        }

        @Test
        void anExpiredHoldIs410() throws Exception {
            when(initiatePayment.initiate(org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new InitiatePayment.Result.HoldExpired(88L, NOW.minusSeconds(1)));

            mvc.perform(post("/api/v1/bookings/88/pay").header("Authorization", bearer()))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("API-7 and API-9")
    class CancellingAndReading {

        @Test
        void aCancellationReportsTheRefund() throws Exception {
            when(cancelBooking.cancel(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(
                            new CancelBooking.Outcome.Cancelled(
                                    88L, 90_000L, CancelBooking.RefundSettlement.COMPLETED));

            mvc.perform(
                            post("/api/v1/bookings/PNR1/cancel")
                                    .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.refundPaise").value(90_000));
        }

        /** FR-43: a released hold is not a cancellation with a zero refund. */
        @Test
        void aReleasedHoldSaysSoRatherThanReportingAZeroRefund() throws Exception {
            when(cancelBooking.cancel(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new CancelBooking.Outcome.Released(88L));

            mvc.perform(
                            post("/api/v1/bookings/PNR1/cancel")
                                    .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPIRED"))
                    .andExpect(jsonPath("$.refundPaise").doesNotExist());
        }

        @Test
        void theCallersIdentityComesFromTheToken() throws Exception {
            when(cancelBooking.cancel(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new CancelBooking.Outcome.Released(88L));

            mvc.perform(post("/api/v1/bookings/PNR1/cancel").header("Authorization", bearer()));

            verify(cancelBooking)
                    .cancel(
                            org.mockito.ArgumentMatchers.eq("PNR1"),
                            org.mockito.ArgumentMatchers.eq(TOKEN_USER),
                            any());
        }

        @Test
        void anUnknownBookingIs404() throws Exception {
            when(cancelBooking.cancel(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new CancelBooking.Outcome.UnknownBooking("PNR1"));

            mvc.perform(
                            post("/api/v1/bookings/PNR1/cancel")
                                    .header("Authorization", bearer()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void bookingDetailIsReturnedForItsOwner() throws Exception {
            when(bookingRepository.findByPnr("PNR1")).thenReturn(Optional.of(confirmed(TOKEN_USER)));

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/bookings/PNR1")
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pnr").value("PNR1"))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }

        /** Anything else makes API-9 an oracle for enumerating other people's PNRs. */
        @Test
        void anotherUsersBookingReadsAsAbsent() throws Exception {
            when(bookingRepository.findByPnr("PNR1"))
                    .thenReturn(Optional.of(confirmed(TOKEN_USER + 1)));

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/bookings/PNR1")
                            .header("Authorization", bearer()))
                    .andExpect(status().isNotFound());
        }

        private BookingRepository.BookingView confirmed(long owner) {
            return new BookingRepository.BookingView(
                    88L,
                    Optional.of("PNR1"),
                    BookingStatus.CONFIRMED,
                    pool(),
                    new SegmentRange(0, 4),
                    1,
                    245_000L,
                    owner,
                    Optional.empty(),
                    List.of(7L));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void givenHeld() {
        when(holdSeats.handle(any()))
                .thenReturn(
                        new HoldSeats.Result.Held(
                                88L,
                                List.of(7L),
                                NOW.plusSeconds(120),
                                245_000L,
                                pool(),
                                new SegmentRange(0, 4)));
    }

    private static PoolKey pool() {
        return new PoolKey(1L, TravelClass.AC3, QuotaType.TATKAL);
    }

    private String bearer() {
        return "Bearer " + jwt.issue(TOKEN_USER);
    }

    private ResultActions authenticatedHold(String key, String body) throws Exception {
        return mvc.perform(
                post("/api/v1/bookings/hold")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String holdBody() {
        return """
               {"scheduleId":1,"travelClass":"3A","quotaType":"TATKAL",
                "fromStationCode":"NDLS","toStationCode":"BCT",
                "passengers":[{"name":"A","age":30,"gender":"M"}]}
               """;
    }
}
