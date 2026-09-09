package io.tatkalrush.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.tatkalrush.adapters.web.auth.JwtAuthFilter;
import io.tatkalrush.adapters.web.auth.StubJwt;
import io.tatkalrush.application.usecases.SearchTrains;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code API-1} at the HTTP boundary.
 *
 * <p>The one worth reading is {@code approximateIsStatedInEveryResponse}. FR-14
 * requires search results to be "explicitly labelled as approximate", and a
 * requirement satisfied only in a javadoc is satisfied for nobody holding the
 * response.
 */
class SearchControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final long TOKEN_USER = 4_242L;
    private static final LocalDate JOURNEY = LocalDate.of(2026, 10, 2);

    private SearchTrains searchTrains;
    private StubJwt jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        searchTrains = mock(SearchTrains.class);
        jwt = new StubJwt("a-test-secret", Duration.ofHours(1), InstantSource.fixed(NOW));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SearchController(searchTrains, InstantSource.fixed(NOW)))
                        .addFilters(new JwtAuthFilter(jwt))
                        .build();
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("a search that finds trains")
    class Found {

        @BeforeEach
        void stubOneTrain() {
            when(searchTrains.search(any())).thenReturn(oneTrain());
        }

        @Test
        void everyClassIsRendered() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trains.length()").value(1))
                    .andExpect(jsonPath("$.trains[0].trainNumber").value("12951"))
                    .andExpect(jsonPath("$.trains[0].classes.length()").value(2));
        }

        @Test
        void approximateIsStatedInEveryResponse() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(jsonPath("$.approximate").value(true))
                    // FR-14 wants the label, not a flag nobody reads. A client that
                    // treats this number as a reservation will be surprised during
                    // exactly the spike this project exists to survive.
                    .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("FR-14")));
        }

        @Test
        void theWireCarriesTheClassCodeNotTheEnumConstant() throws Exception {
            // "3A" is not a legal Java identifier, so the constant is AC3. A client
            // echoing what it received into a hold request must send "3A" - the
            // enum name would fail at TravelClass.fromCode.
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(jsonPath("$.trains[0].classes[1].travelClass").value("3A"));
        }

        @Test
        void aLockedPoolCarriesItsOpeningInstant() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(jsonPath("$.trains[0].classes[0].bookable").value(false))
                    .andExpect(jsonPath("$.trains[0].classes[0].opensAt").exists());
        }

        @Test
        void anUnreadablePoolIsNullNotZero() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(jsonPath("$.trains[0].classes[1].availableBerths").doesNotExist());
        }

        @Test
        void theCacheStatsAreReported() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null)
                    .andExpect(jsonPath("$.cache.hits").value(3))
                    .andExpect(jsonPath("$.cache.misses").value(1));
        }

        @Test
        void theClockReachesTheUseCase() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), null);

            var command = ArgumentCaptor.forClass(SearchTrains.SearchCommand.class);
            verify(searchTrains).search(command.capture());

            // FR-31: the Tatkal window is decided from an injected clock. A use case
            // reading Instant.now() itself could not be tested at the boundary.
            assertEquals(NOW, command.getValue().now());
            assertEquals(JOURNEY, command.getValue().journeyDate());
            assertEquals(Optional.empty(), command.getValue().travelClass());
        }

        @Test
        void theClassFilterIsParsedFromItsCode() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), "3A");

            var command = ArgumentCaptor.forClass(SearchTrains.SearchCommand.class);
            verify(searchTrains).search(command.capture());

            assertEquals(Optional.of(TravelClass.AC3), command.getValue().travelClass());
        }
    }

    // ── rejections ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requests that never reach the use case")
    class Rejected {

        @Test
        void aRequestWithoutATokenIsRejected() throws Exception {
            mvc.perform(
                            get("/api/v1/trains/search")
                                    .queryParam("from", "NDLS")
                                    .queryParam("to", "BCT")
                                    .queryParam("date", JOURNEY.toString()))
                    .andExpect(status().isUnauthorized());

            // §19.5 sizes the harness on the assumption that searches count against
            // FR-60's per-user cap. An unauthenticated search would make that
            // arithmetic wrong in the direction that hides a problem.
            verify(searchTrains, never()).search(any());
        }

        @Test
        void anUnparseableDateIsABadRequest() throws Exception {
            search("NDLS", "BCT", "next tuesday", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            verify(searchTrains, never()).search(any());
        }

        @Test
        void anUnknownClassCodeIsABadRequest() throws Exception {
            search("NDLS", "BCT", JOURNEY.toString(), "SLEEPER")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("SLEEPER")));

            verify(searchTrains, never()).search(any());
        }

        @Test
        void theSameStationTwiceIsABadRequest() throws Exception {
            // Not merely empty: the query requires from.seq < to.seq, so this finds
            // nothing, and "no trains" would send the caller looking at timetables
            // for a journey that is not a journey.
            search("NDLS", "NDLS", JOURNEY.toString(), null)
                    .andExpect(status().isBadRequest());

            verify(searchTrains, never()).search(any());
        }

        @Test
        void anUnknownStationIsFourHundredNotFourOhFour() throws Exception {
            when(searchTrains.search(any()))
                    .thenReturn(new SearchTrains.Result.UnknownStation(List.of("XXXX")));

            // The search resource exists perfectly well; it is the parameter that
            // names nothing.
            search("NDLS", "XXXX", JOURNEY.toString(), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("XXXX")));
        }
    }

    @Test
    void aRouteWithNoTrainsIsAnEmptyListNotAnError() throws Exception {
        when(searchTrains.search(any()))
                .thenReturn(new SearchTrains.Result.Found(List.of(), 0, 0));

        search("NDLS", "BCT", JOURNEY.toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trains.length()").value(0))
                .andExpect(jsonPath("$.approximate").value(true));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions search(
            String from, String to, String date, String travelClass) throws Exception {

        var request =
                get("/api/v1/trains/search")
                        .header("Authorization", "Bearer " + jwt.issue(TOKEN_USER))
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .queryParam("date", date);
        if (travelClass != null) {
            request = request.queryParam("class", travelClass);
        }
        return mvc.perform(request);
    }

    /**
     * One train with a locked TATKAL pool and an unreadable AC3 pool — the two
     * cases whose rendering is easy to get wrong.
     */
    private static SearchTrains.Result.Found oneTrain() {
        var locked =
                new SearchTrains.ClassAvailability(
                        TravelClass.SL,
                        QuotaType.TATKAL,
                        8,
                        8,
                        false,
                        false,
                        Instant.parse("2026-10-01T05:30:00Z"));

        var unreadable =
                new SearchTrains.ClassAvailability(
                        TravelClass.AC3, QuotaType.GENERAL, null, 64, false, true, null);

        return new SearchTrains.Result.Found(
                List.of(
                        new SearchTrains.TrainAvailability(
                                1L,
                                "12951",
                                "Mumbai Rajdhani",
                                true,
                                new SegmentRange(0, 4),
                                LocalTime.of(16, 55),
                                LocalTime.of(8, 35),
                                new BigDecimal("730.00"),
                                List.of(locked, unreadable))),
                3,
                1);
    }
}
