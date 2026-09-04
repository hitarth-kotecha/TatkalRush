package io.tatkalrush.domain.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a TATKAL pool unlocks (FR-28 to FR-31).
 *
 * <p><b>A pure function of clock time, never a scheduled job</b> (FR-30). That
 * looks like an implementation preference and is not, for two reasons.
 *
 * <p>A job creates a window in which the clock says 10:00 but the job has not run
 * yet, so the pool's state depends on whether a background thread was scheduled —
 * which is not a property anything can reason about, and which differs between
 * replicas.
 *
 * <p>Worse, it creates <b>an artificial thundering herd on the job itself</b>.
 * Every waiting client polls, the job flips a flag, and the herd arrives at
 * whatever instant the scheduler happened to fire. A pure function has no such
 * instant: every request, on every replica, independently computes the same
 * answer from the same clock.
 *
 * <p>All times are Indian Standard Time, which is UTC+5:30 and has no daylight
 * saving — so a fixed offset is exact here in a way it would not be for most
 * zones. {@link #IST} is still a {@code ZoneId} rather than a raw offset, so that
 * this stays correct if the rule is ever expressed for another region.
 */
public final class TatkalWindow {

    /** IST. No DST, so the offset is constant, but named rather than hardcoded. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** FR-28: AC classes open at 10:00 IST on D-1. */
    public static final LocalTime AC_OPEN_TIME = LocalTime.of(10, 0);

    /** FR-28: sleeper opens an hour later, at 11:00 IST on D-1. */
    public static final LocalTime SLEEPER_OPEN_TIME = LocalTime.of(11, 0);

    private TatkalWindow() {}

    /**
     * The instant the TATKAL pool for this journey opens.
     *
     * <p><b>D-1 is the previous calendar day, not 24 hours before departure.</b> A
     * train leaving on 2 October opens on 1 October at 10:00 IST regardless of
     * whether it departs at 06:00 or at 23:55 — so the gap between opening and
     * departure varies, and computing it as a fixed offset from departure would
     * be wrong for every train that is not a 10:00 departure.
     */
    public static Instant opensAt(LocalDate journeyDate, TravelClass travelClass) {
        LocalTime openTime =
                travelClass == TravelClass.SL ? SLEEPER_OPEN_TIME : AC_OPEN_TIME;
        return ZonedDateTime.of(journeyDate.minusDays(1), openTime, IST).toInstant();
    }

    /**
     * Whether TATKAL is open for this journey at {@code now}.
     *
     * <p>{@code now} is passed rather than read, so FR-31's injected {@code Clock}
     * has somewhere to inject to. FR-31 calls that mandatory: a test suite that
     * cannot control time cannot test this system, and waiting until 10 AM to run
     * a test is not a strategy.
     */
    public static boolean isOpen(LocalDate journeyDate, TravelClass travelClass, Instant now) {
        // Inclusive at the boundary: at exactly 10:00:00.000 the pool is OPEN.
        // The boundary has to fall somewhere, and opening on the instant matches
        // what a user reading "opens at 10:00" expects.
        return !now.isBefore(opensAt(journeyDate, travelClass));
    }

    /**
     * Whether a pool may be allocated from at all.
     *
     * <p>GENERAL is always open (FR-8); only TATKAL has a window. Answering for
     * both here keeps the caller from having to remember which quotas are gated,
     * which is the kind of thing that gets forgotten in one code path out of
     * three.
     */
    public static boolean isPoolOpen(
            QuotaType quotaType, LocalDate journeyDate, TravelClass travelClass, Instant now) {
        return quotaType == QuotaType.GENERAL || isOpen(journeyDate, travelClass, now);
    }
}
