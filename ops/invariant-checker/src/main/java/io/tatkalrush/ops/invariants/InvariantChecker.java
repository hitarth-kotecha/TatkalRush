package io.tatkalrush.ops.invariants;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs §14's checks and reports what failed. NFR-9: any violation fails the build.
 *
 * <h2>Two modes, and the distinction is not a nicety</h2>
 *
 * <p>§14 says INV-5, INV-8 and INV-12 "run <b>only</b> after the system quiesces,
 * since during load a transient divergence is expected and legitimate". A free
 * count read between two Redis writes is momentarily stale; that is a description
 * of concurrency, not a defect.
 *
 * <p>So {@link Mode#CONTINUOUS} <b>skips</b> those rather than running them with a
 * caveat attached. Running them mid-run would produce failures that everyone
 * learns to dismiss — and a team that has learned to dismiss INV-12 will dismiss
 * INV-11 the day it finally fires. A check that cries wolf does not merely fail to
 * help; it degrades the checks standing beside it.
 *
 * <p>The report says which checks were skipped and why, so "all green" is never
 * mistaken for "everything was asked".
 */
public final class InvariantChecker {

    public enum Mode {
        /** Mid-run. Only invariants that hold at every instant are evaluated. */
        CONTINUOUS,
        /** After the system is at rest. Everything runs. */
        QUIESCED
    }

    public record Result(String id, String description, List<String> violations, boolean skipped) {

        public boolean passed() {
            return skipped || violations.isEmpty();
        }
    }

    public record Report(Mode mode, List<Result> results) {

        /** NFR-9's verdict. A skipped check is not a failure, and not a pass either. */
        public boolean passed() {
            return results.stream().allMatch(Result::passed);
        }

        public List<Result> failures() {
            return results.stream().filter(r -> !r.passed()).toList();
        }

        /** Plain text, because this is read in a terminal and pasted into an issue. */
        public String render() {
            var out = new StringBuilder();
            out.append("Invariant check (").append(mode).append(")\n");
            out.append("=".repeat(60)).append('\n');

            for (Result result : results) {
                String verdict = result.skipped() ? "SKIP" : result.violations().isEmpty() ? "PASS" : "FAIL";
                out.append("%-4s %-7s %s%n".formatted(verdict, result.id(), result.description()));

                for (String violation : result.violations()) {
                    out.append("         ").append(violation).append('\n');
                }
            }

            out.append("=".repeat(60)).append('\n');
            long skipped = results.stream().filter(Result::skipped).count();
            if (skipped > 0) {
                // Stated explicitly. "All green" and "all green, six not asked"
                // are different claims, and only one of them is worth publishing.
                out.append(
                        "%d check(s) skipped: they require a quiesced system (§14).%n"
                                .formatted(skipped));
            }
            out.append(passed() ? "PASSED\n" : "FAILED - see NFR-9\n");
            return out.toString();
        }
    }

    private final List<Invariant> invariants;

    public InvariantChecker(List<Invariant> invariants) {
        this.invariants = List.copyOf(invariants);
    }

    public static InvariantChecker standard() {
        return new InvariantChecker(SqlInvariants.all());
    }

    public Report run(Connection connection, Mode mode) {
        var context = new CheckContext(connection);
        var results = new ArrayList<Result>();

        for (Invariant invariant : invariants) {
            if (invariant.quiesceOnly() && mode == Mode.CONTINUOUS) {
                results.add(new Result(invariant.id(), invariant.description(), List.of(), true));
                continue;
            }
            results.add(
                    new Result(
                            invariant.id(),
                            invariant.description(),
                            invariant.violations(context),
                            false));
        }

        return new Report(mode, results);
    }
}
