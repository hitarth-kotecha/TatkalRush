# loadtest

k6 profiles (§19), chaos scripts and the report generator.

**Outside the Maven reactor** by design (DD-005): binding the k6/npm lifecycle to
Maven phases couples unrelated builds and defeats Docker layer caching for both.

## Layout

| | |
|---|---|
| `extract-targets.sh` | Writes `targets.json` from the running database. **Run this first.** |
| `lib/api.js` | Shared harness: identity, requests, FR-51 outcome classification |
| `calibration/http-ceiling.js` | AC-0.7's Phase 0 ramp against `/actuator/health` |
| `calibration/nfr-ramp.js` | AC-1.13's ramp against `search` and `hold` |
| `calibration/run-nfr-calibration.sh` | Driver: steps the rate, finds the knee, voids bad steps |
| `lib/preflight.sh` | Refuses to measure unless every request through nginx reaches a booking replica (DD-045) |
| `lib/host-paging.ps1` | Samples the Windows host's hard page-ins during a run; heavy paging voids it |
| `profiles/p1-tatkal-spike.js` | §19.1 P1: ramp to `PEAK_RPS` of TATKAL holds on the one open date |
| `profiles/p2-sustained-mixed.js` | §19.1 P2: `RATE` sustained, 90 % search / 10 % hold |
| `run-profile.sh` | Driver: preflight → warm-up → reset → run → **drain** → §14 → §19.5 verdict |
| `results/` | Per-run JSON. Not committed. |
| `targets.json` | Generated. Not committed — it describes one seeded database. |

## The four rules a step is void under

Learned by breaking each one, and enforced by the driver rather than remembered.

1. **Warm up first.** Cold, this harness reported p99 487 ms at 20 rps. It was JIT;
   p50 in the same step was 11 ms.
2. **A step that drops iterations measured the load generator**, not the system.
3. **Do not ramp past the knee.** Nothing is learned above it and the damage
   contaminates whatever runs next.
4. **Holds consume inventory.** A step that exhausts its pools stops timing
   allocation and starts timing a free-count read that returns zero. Inventory is
   reset between hold steps, and every step reports what fraction of it was a real
   allocation.

And one that is not about the system at all: **anything the harness does inside the
measurement window is attributed to the system.** Authenticating several hundred
virtual users lazily made an identical step read 541.8 ms and 21.6 ms depending on
how many VUs happened to be warm. Tokens come from `setup()` now.

## Validity (§19.5)

A run is valid only if `rate_limited_total == 0`,
`allocation_constraint_violations_total == 0`, and every §14 invariant passes. The
first is a **harness** property, not a system one: it means requests were refused
at the edge before reaching the system under test, so any throughput figure
describes load that was never served.

`run-profile.sh` adds five of the same class, each learned from a run that looked
fine until it was examined: **any `TOO_MANY_HOLDS`** (FR-20 refused load the harness
shaped), **any dropped iteration**, **any request failure**, **a system that did
not drain** within TTL + 60 s after the run — because then the quiesced invariants
were asked about a live system — and **a host that was paging** (p90 above 1,000
hard page-ins/s during the run, Windows only). Docker Desktop's VM is a Windows
process, and when the laptop runs short of RAM its memory — including the replicas'
Java heaps — is read back from the pagefile. The guest sees nothing; its JVMs report
multi-second GC pauses. Close memory-hungry applications before a benchmark run.

The VU count is what prevents the first of those, and it is derived rather than
chosen:

```
VUs  >=  rate / 5                        FR-60's 10 rps per-user cap, with headroom
VUs  >=  rate * ttlSeconds / 3 * 1.5     FR-20's 3 active holds, with headroom (DD-044)
```

The second dominates. At FR-17's 120 s it is why `hold` could not be ramped past
150 rps (`docs/benchmarks/001-nfr-calibration.md`), and why the profiles run against
a stack started with `TATKAL_HOLD_TTL_MS=15000` (DD-044). The driver reads the TTL
from the running container rather than trusting its own environment.

## Quiesced means drained, not "waited a while"

INV-5, INV-8 and INV-12 compare Redis with Postgres, and a live hold makes them
disagree legitimately. So after a profile the driver **waits for §13.2's reaper**
until no `holds:` key and no `HELD` booking remain, and reports how long that took.
It used to sleep and then "nudge" lazy reaping with a burst of holds — which only
touched the pools it booked from, and left live holds of its own for INV-8 to find.

## Running

See `docs/runbook.md` for bringing the stack up, seeding, and warming Redis. Then:

```bash
./loadtest/extract-targets.sh
./loadtest/calibration/run-nfr-calibration.sh
```

The profiles need an open Tatkal window and the benchmark TTL, set when the stack
starts (`TATKAL_REDIS_PORT` only if Windows has reserved 6379 — see the runbook):

```bash
TATKAL_CLOCK_OFFSET=P40D TATKAL_HOLD_TTL_MS=15000 docker compose up -d --wait
```

```bash
PEAK_RPS=60 ./loadtest/run-profile.sh p1
```

```bash
RATE=550 ./loadtest/run-profile.sh p2
```
