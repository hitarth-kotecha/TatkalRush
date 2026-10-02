# chaos

§19.2's four scenarios (C1–C4), each a thin wrapper around
[`run-profile.sh`](../run-profile.sh) rather than a separate harness: every
scenario still gets §19.5's validity gate, the §14 invariant pass, the drain
wait, and the result JSON — the only new thing each script adds is *when* and
*what* to break.

| | Injection | Under | Expects |
|---|---|---|---|
| [`c1-kill-replica.sh`](c1-kill-replica.sh) | `docker compose kill` one app replica | P1 | Strategy B rebalances onto the survivor; Strategy A: near-zero impact |
| [`c2-redis-flushall.sh`](c2-redis-flushall.sh) | `redis-cli FLUSHALL`, then the explicit rebuild (DD-040) | P2 | in-flight holds lost, confirmed bookings intact, masks + free counts rebuilt |
| [`c3-postgres-freeze.sh`](c3-postgres-freeze.sh) | toxiproxy disables the Postgres proxy for 5 s | P2 | writes 503, reads keep serving from Redis, recovery on release |
| [`c4-kafka-restart.sh`](c4-kafka-restart.sh) | `docker compose restart kafka` | P1 | Strategy B buffers/retries, no double-apply; Strategy A: non-event |

## Running one

```bash
./loadtest/chaos/c1-kill-replica.sh
```

C3 is the odd one out — it needs the app's Postgres connection routed through
toxiproxy, which the default stack deliberately does not do (see
[`compose.chaos.yaml`](compose.chaos.yaml)). Start the stack with the overlay
before running it:

```bash
TATKAL_CLOCK_OFFSET=P40D TATKAL_HOLD_TTL_MS=15000 \
  docker compose -f compose.yaml -f loadtest/chaos/compose.chaos.yaml up -d --wait
./loadtest/chaos/c3-postgres-freeze.sh
```

AC-2.3 and AC-2.6 both want every scenario run against **both** allocator
strategies. Restart the stack with `TATKAL_ALLOCATOR_STRATEGY=single-writer`
between runs (default is `redis-lua`) — `run-profile.sh` reads it from the
running container and stamps `allocator_strategy` into every result JSON, so
the two runs stay distinguishable without hand-tracking which was which.

## How injection is timed

`run-profile.sh` grew two generic env vars for this (documented in its own
header): `CHAOS_CMD`, a shell command backgrounded with a delay, and
`CHAOS_DELAY_SECONDS`, measured from when the **real** k6 run starts — never
the discarded warm-up, and never wall-clock time from when the chaos script
itself was launched, both of which would make the delay depend on how long
warm-up and reset happened to take on this machine rather than on the load
shape the scenario names.

C1 and C4 land their injection mid-hold-phase (ramp seconds + half the hold
duration), not at the exact instant the ramp completes, because k6's own
`setup()` (a token fetch per VU) runs before the ramp starts and takes longer
at higher VU counts — landing anywhere inside the hold phase is still "at
spike peak" and tolerates a slow `setup()` without needing to synchronise on
it exactly.

## Why a chaos run's "failures" aren't a §19.5 violation

§19.5 names exactly three validity criteria: `rate_limited_total == 0`,
`allocation_constraint_violations_total == 0`, and every §14 invariant
passing. `run-profile.sh` layers five more of its own for *ordinary* profile
runs — one of them voids a run with any request failure at all, because on a
clean run a 5xx means the harness or the system is wrong.

A chaos scenario breaks that assumption on purpose: C2 and C3 are specifically
about the write path failing correctly for a few seconds, and C1/C4 can
legitimately produce `RETRY_LATER` (DD-006's fencing response, HTTP 503) while
a partition rebalances. `lib/api.js` now classifies `RETRY_LATER` as its own
outcome rather than a generic failure, the same way `SEAT_UNAVAILABLE` already
is (FR-51) — but a real connection reset from a killed container still can't
be given a business classification, so `run-profile.sh` accepts a
`CHAOS_SCENARIO` env var (each script here sets it) that turns a non-zero
`failures` count into a printed note instead of an invalidation reason, for
that run only. §19.5's own three criteria are never relaxed — a constraint
violation during a chaos run means an allocator bug shipped, chaos or not.

## What these scripts cannot check yet, stated rather than hidden (NFR-14)

- **`dedup_evictions_before_window_expiry_total` (AC-2.6).**
  `KafkaSeatAllocator`/`DedupCache` track this as a plain in-JVM counter; it is
  not wired to a `MeterRegistry`, so `/actuator/prometheus` does not expose it
  and no script here can read it from outside the process.
- **NFR-8's ≤2 s warm-recovery bound (C1, C4) is measured, not met.**
  `partition_replay_duration_seconds` (tagged by partition) is now recorded
  around every `replayPartition()` call, regardless of outcome - the number
  exists. It is expected to exceed the budget anyway: [DD-049](../../docs/design-decisions.md#dd-049)
  found that replay always starts from offset 0, never from the checkpoint it
  just wrote, and wiring the metric does not change that. `RETRY_LATER`'s count
  and where it falls in the run remains a useful secondary signal, but the
  Timer is the number to read first.
- **A per-second timeline for C2/C3's fault window.** The result JSON is a
  single post-run summary. Confirming that failures/`RETRY_LATER` cluster
  inside the injection window rather than spreading across the whole run is,
  for now, a Grafana screenshot or a rerun with k6's own `--out`, not something
  these scripts assert on their own.

None of these are silently skipped — each script's own trailer prints exactly
what it did and did not check.

## Not yet done

These scripts are written and syntax-checked, not run. Nobody has yet brought
up the stack and executed C1–C4 against a live system, for either allocator
strategy — that is the actual point of Phase 2 (§9.4) and is still open. The
`compose.chaos.yaml` overlay's merge behaviour was checked with `docker
compose config` (renders the merged config without starting anything) but the
proxy itself has never been exercised against a running app.
