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

The VU count is what prevents it, and it is derived rather than chosen:

```
VUs  >=  rate / 5                 FR-60's 10 rps per-user cap, with headroom
VUs  >=  rate * seconds / 3       FR-20's 3 active holds, over FR-17's 120 s TTL
```

The second dominates by two orders of magnitude and is the reason `hold` cannot
currently be ramped past 150 rps on this machine — see
`docs/benchmarks/001-nfr-calibration.md`.

## Running

See `docs/runbook.md` for bringing the stack up, seeding, and warming Redis. Then:

```bash
./loadtest/extract-targets.sh
./loadtest/calibration/run-nfr-calibration.sh
```
