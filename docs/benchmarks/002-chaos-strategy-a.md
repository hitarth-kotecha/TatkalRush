# 002 — Chaos suite, Strategy A (redis-lua), first live runs

**Date:** 2026-10-04 · **Allocator:** `redis-lua` (the default) · **Scenarios:** C1–C4 · **Strategy B:
not run — see "What this does not cover".**

Hardware, JDK and the shared-laptop caveat are as in [000](000-calibration.md): i5-10300H, 7.91 GB,
Windows 10 + WSL2, Temurin 25, **k6 co-located**. Stack started with `TATKAL_CLOCK_OFFSET=P16D`
and `TATKAL_HOLD_TTL_MS=15000` (DD-044). **Deviation:** Grafana and Prometheus were stopped for the
later runs to claw back ~250 MB of VM memory; the host had 1.45 GB free before anything started.

Failed and voided runs are kept, per NFR-14.

## Results

| Scenario | Profile | §19.5 verdict | §14 invariants | Failures | Why that verdict |
|---|---|---|---|---|---|
| C1 (first run) | P1, 60 rps | **valid** | 12/12 | 0 | `app-1` was killed and **never came back** — see finding 1, so this run is not the scenario it claims |
| C1 (fixed) | P1, 60 rps | void | 12/12 | 7 | host paging p90 1,049/s (limit 1,000) |
| C2 | P2, 200 rps | void | 12/12 | 213 | host paging p90 2,010/s |
| C3 | P2, 200 rps | **valid** | 12/12 | 837 | — |
| C4 | P1, 60 rps | void | 12/12 | 0 | host paging p90 1,154/s |

"Void" means DD-048's guard fired, not that the system misbehaved: every invariant held in every run.
Only C3 is a publishable run. The others are evidence of correctness (invariants) and of behaviour
(counts), not of latency.

**C2 in detail.** `FLUSHALL` then the explicit rebuild (DD-040) took 13.2 s for 3,600 pools /
291,120 berth slots. INV-5, INV-8 and INV-12 pass after it — §19.2's stated criterion. 213 requests
failed and 4,263 iterations were dropped while Redis was busy rebuilding.

**C4 under Strategy A** was a non-event, as §8.1 predicts: nothing on the write path uses Kafka.
Zero failures; the broker was healthy again within about a minute.

## Findings

1. **`docker kill` does not trigger `restart: unless-stopped`.** The container stayed `Exited (137)`.
   C1 as first written therefore tested "a replica dies and stays dead", not crash-and-recover. It
   now kills, waits `DOWN_SECONDS`, and starts the container explicitly.
2. **P2's write pattern aliased with k6's VU rotation.** Writes are every 10th iteration and k6 hands
   iterations to VUs in strict rotation, so with a VU count that is a multiple of 10 only 1 in 10
   users ever wrote. At 200 rps (150 VUs) that was 15 users: 4,464 of 5,881 holds came back
   `TOO_MANY_HOLDS`, reproduced with no chaos. The default 550 rps gives 413 VUs and never showed it.
   The profile now keeps the VU count coprime with the write period.
3. **The toxiproxy image has no shell.** The first `compose.chaos.yaml` registered the proxy with a
   `/bin/sh` one-shot and could not start; the failed `up` left three app containers created but not
   running. The proxy now loads from a config file. Separately, Git Bash rewrites `/toxiproxy-cli` into a
   Windows path; C3 now disables that conversion for its `docker exec` calls.
4. **A Postgres outage returns 500, not the behaviour §13.1 specifies.** With the proxy disabled,
   probed directly: search → **500 after 3.0 s**, hold → **500 after 3.0 s**, token mint → 200 (no
   database). §13.1 and C3's expectation say writes get 503 and reads are served from cache. Neither
   holds: search needs Postgres to resolve the route (only the availability *counts* are cached,
   FR-15's 2 s), and no handler in the web layer maps an unreachable database to 503. In the C3 run
   837 of roughly 900 requests in the 5 s window failed. **Invariants held — slow, never wrong —
   but the specified degradation is not implemented.**
5. **Cold start took ~3.5 minutes, not 32 s** (AC-0.1 budget 120 s) and Kafka briefly went unhealthy
   with broker-heartbeat timeouts while three JVMs started on a starved host. Not a code change; it is
   what 1.45 GB of free host memory does.

## What this does not cover

- **Strategy B was not run.** The harness cannot drive it yet: `compose.yaml` does not pass
  `TATKAL_ALLOCATOR_STRATEGY` to the apps (so the strategy cannot be selected), the Kafka listener
  advertises `kafka:9092` so the host-side `SwpPoolWarmupMain` cannot reach it, and `run-profile.sh`'s
  reset and the INV-5/8/12 checks are Redis-only. Under Strategy B the invariant stage would pass
  **vacuously** — profiles only hold, never confirm, so `seat_allocations` is empty and the stale Redis
  masks agree with it. C1/C4 are the scenarios that matter for B; none of their B-side questions
  (NFR-8, `partition_replay_duration_seconds`, `dedup_evictions_before_window_expiry_total`) are
  answered here.
- No per-second timeline: whether failures cluster inside the injection window is inferred from counts
  and the direct probes above, not read from a series.
