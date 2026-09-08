package io.tatkalrush.ops.invariants;

import java.util.List;

/**
 * One of §14's twelve checks.
 *
 * <h2>These do not share code with what they check</h2>
 *
 * <p>An invariant that asked a repository, a use case or {@code SegmentMask} for
 * its answer would get the same wrong answer twice when the code has a bug, and
 * report green. INV-7 states the principle for itself — the expected fare is
 * "<b>recomputed independently</b>" and must not read what the booking stored —
 * and it applies to all of them.
 *
 * <p>So these are raw SQL and arithmetic from first principles. Where a check has
 * to know a storage <em>format</em> (INV-8 decoding Redis masks), it decodes the
 * bytes rather than calling the encoder. Sharing a format is unavoidable; sharing
 * logic would make the check an echo.
 */
public interface Invariant {

    /** {@code "INV-4"}. Used in reports and to select checks by name. */
    String id();

    /** The property, in the words §14 uses. */
    String description();

    /**
     * Whether this can only be trusted once the system is at rest.
     *
     * <p>§14: INV-5, INV-8 and INV-12 "run <b>only</b> after the system quiesces,
     * since during load a transient divergence is expected and legitimate". A free
     * count read between two Redis writes is momentarily stale — that is a
     * description of concurrency, not a defect.
     *
     * <p>{@link InvariantChecker} refuses to run these continuously rather than
     * running them with a caveat, because a check that cries wolf teaches people to
     * dismiss the checks standing next to it.
     */
    default boolean quiesceOnly() {
        return false;
    }

    /**
     * @return the violating rows, empty when the invariant holds. Rows rather than
     *     a boolean: "INV-4 failed" is useless at the end of a soak, where
     *     "schedule 4412, class 3A, segment 2 — 73 allocations against 72 capacity"
     *     says where to look.
     */
    List<String> violations(CheckContext context);
}
