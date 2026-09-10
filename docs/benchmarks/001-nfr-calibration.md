# 001 — NFR-1 and NFR-2 from real endpoints (AC-1.13)

**Date:** 2026-09-10 · **Phase:** 1c · **Gate:** AC-1.13 · **Decisions:** [DD-019](../design-decisions.md#dd-019), [DD-041](../design-decisions.md#dd-041)
**Git SHA:** `6bdcec5`

[000-calibration.md](000-calibration.md) measured the machine's HTTP ceiling with
no domain work in the path and found 750 rps. It said plainly that real endpoints
would be a fraction of that and left NFR-1 and NFR-2 blank. This fills them in.

---

## NFR-12 metadata

Required on every reported number, without exception.

| | |
|---|---|
| **CPU** | Intel Core i5-10300H @ 2.50 GHz — 4 physical cores, 8 logical |
| **Host RAM** | 7.91 GB total |
| **OS** | Windows 10 Home Single Language 19045 |
| **Docker** | 29.5.3, WSL2 backend — 8 CPUs, 3.78 GiB allocated to the VM |
| **JDK (container)** | Temurin **25.0.4+7** LTS, `--enable-preview` |
| **Spring Boot** | 4.0.8 |
| **Load generator** | k6 **v2.2.0**, **co-located on the same machine** (NFR-13) |
| **Topology** | nginx round-robin → 2 app replicas (`app-1`, `app-2`) |
| **Allocator** | Strategy A (`redis-lua`) |
| **Dataset** | seed `20261001` — 20 trains, 600 schedules, 3,600 pools, 291,120 bookable berth-instances, 5,000 users |
| **Targets** | 1,200 pools across journey dates 2026-10-21 … 2026-10-30 |
| **Clock offset** | **`P40D`** (DD-041) — see below |
| **Step** | 30 s per rate for `search`, 20 s for `hold`, after a 30 s discarded warmup |
| **Settle** | 15 s idle after every inventory reset |

**The clock offset is part of the metadata, not a footnote.** The stack ran 40 days
ahead of wall-clock so the seeded dataset's Tatkal windows were open (AC-1.11). A
run whose windows were shut answers `QUOTA_LOCKED` before touching the allocator
and is not comparable to this one.

**k6 shares this laptop with the system under test.** Headroom on dedicated
infrastructure would be higher. No estimate of how much higher is offered, per
NFR-13 — and for `hold` below, the load generator is the binding constraint, which
makes that refusal load-bearing rather than ceremonial.

---

## Result

| | Measured | Budget breached at | Status |
|---|---:|---:|---|
| **NFR-1** — `search` | **550 rps** | 700 rps (p99 57 ms > NFR-5's 50 ms) | measured |
| **NFR-2** — `hold` | **≥ 150 rps** | *not reached* | **floor only** |

---

## `search` — NFR-1

Ramped until p99 breached NFR-5's 50 ms.

| Requested rps | Achieved | p50 ms | p95 ms | p99 ms | |
|---:|---:|---:|---:|---:|---|
| 250 | 249.3 | 4.00 | 8.18 | 14.00 | ok |
| 400 | 398.1 | 3.18 | 9.00 | 17.00 | ok |
| 550 | 547.2 | 4.00 | 15.00 | 43.00 | ok |
| 700 | 695.6 | 5.00 | 20.00 | **57.00** | p99 over 50 ms |

**NFR-1 = 550 rps.** Zero failures, zero `RATE_LIMITED`, zero dropped iterations at
every step.

Reproduced: an earlier run of the same ramp gave 500 rps sustained with a breach at
650. The knee is between 550 and 700 and the reported figure is the lower
observation.

### Against AC-0.7's ceiling — the cost of the domain path

| | rps within budget | ratio |
|---|---:|---:|
| `/actuator/health/liveness` — no I/O at all | 750 | 1.00 |
| `/actuator/health` — one backend round trip | 500 | 0.67 |
| **`GET /trains/search`** — SQL join + Redis, cached 2 s | **550** | **0.73** |

Search costs about **27 %** of the bare HTTP path — and, notably, *less* than the
health endpoint that checks Postgres, Redis, disk and SSL on every call.

That is not search being free. It is FR-15's cache doing what §6.1 claims: an
availability answer is read once per `(pool, range)` per two seconds however many
callers ask for it, whereas `/actuator/health` re-checks four subsystems every
time. The comparison is a useful reminder that "an endpoint that does no domain
work" is not automatically the cheapest thing in the system.

---

## `hold` — NFR-2 is a floor, and the reason matters

| Requested rps | Achieved | p50 ms | p95 ms | p99 ms | real allocations | |
|---:|---:|---:|---:|---:|---:|---|
| 50 | 48.8 | 8.25 | 11.86 | 18.00 | 100 % | ok |
| 100 | 95.5 | 8.37 | 13.01 | 19.34 | 96 % | ok |
| 150 | 138.0 | 8.29 | 13.05 | 27.23 | 91 % | ok |
| 200 | — | — | — | — | — | **VOID** — k6 dropped 420 iterations |

**p99 at 150 rps was 27 ms against a 150 ms budget.** The endpoint is nowhere near
its knee. The load generator reached its own first.

### Why the harness ran out before the system did

FR-20 allows **3 active holds per user**, and FR-17 gives a hold a **120 s TTL** —
so every hold made during a 20 s step is still active when the step ends. A step
therefore needs

```
VUs  ≥  rate × seconds / 3
```

distinct synthetic users, or it fills with `TOO_MANY_HOLDS` and measures the cost
of counting a user's open holds instead of the cost of allocating a berth. §19.1
states this as an assumption ("at P1's 5,000 VUs that is 1:1, so neither FR-60's
10 rps cap nor FR-20's 3-hold limit binds"); it is really a requirement on the VU
count, and it bites hard: 200 rps for 20 s needs **1,334 VUs**.

Each k6 VU carries its own JavaScript runtime. On a box already hosting ten
containers, that is where the run stopped.

Shorter steps do not rescue it. At 10 s the VU ramp-up occupies a large fraction of
the window, and achieved rate falls below requested with **zero** dropped
iterations — 183/200, 225/250, 255/300 — which is the measurement dissolving, not
the system saturating.

**So NFR-2 is recorded as ≥ 150 rps and explicitly not as a ceiling.** Publishing
the harness's limit as the system's would be exactly the fabrication §19.4 exists
to prevent.

### What this does to §19.1's profile magnitudes

P1's spike rate is NFR-2. A floor gives a floor: **P1 runs at ≥ 150 rps**, and the
first thing P1 must report is whether it, too, was harness-bound. The 1 VU : 1 user
rule does not scale away — it is what makes a run valid at all (§19.5).

Two levers exist and both change what is being measured, so neither is applied
here without a decision recorded first:

- shortening `tatkal.hold.ttl` for benchmark runs, which lets a VU cycle through
  more holds but puts reaping on the measured path;
- running k6 off-box, which ends NFR-13's co-location caveat and every number's
  comparability with `000-calibration.md`.

---

## Threats to validity

Stated plainly, because a calibration that oversells itself corrupts everything
built on it.

**Two harness bugs produced plausible numbers before they were found.** Both are
recorded because both were invisible in the output:

1. **A `SharedArray.indexOf` that always returns `-1`.** k6 deserializes a fresh
   object on every `SharedArray` access, so reference equality never holds. The
   target-selection walk therefore started from index 0 every time and half of all
   load landed on **one** pool — 122 of 1,116 holds against a 129-berth pool. The
   result was a wall of `SEAT_UNAVAILABLE` and **p99 2,768 ms**, which read exactly
   like the system's knee. With the spread fixed the identical step gave
   **p99 50.9 ms**. The driver now reports how many distinct pools each step
   touched.

2. **Authentication inside the measurement window.** Tokens were fetched lazily on
   each VU's first iteration, so a step needing 667 VUs opened with a burst of
   ~500 simultaneous token requests competing with the requests being timed. The
   same 100 rps step read **541.8 ms** and **21.6 ms** depending on how many VUs
   were already warm. Tokens now come from `setup()`, which is not attributed to
   any metric.

**A missing settle produced a third.** Starting a timed step immediately after an
inventory reset — 3,600 pools rewritten through Lua, plus a `TRUNCATE` Postgres
then autovacuums — measured the recovery: p99 307 ms where three settled runs of
the identical step gave **19.0, 17.0 and 17.2 ms**.

**Search traffic has no locality here.** Targets are spread uniformly across 1,200
pools, so FR-15's 2-second cache hits far less often than real traffic would, where
everyone searches the same few trains. NFR-1 is therefore a **conservative**
figure. P2 uses the same uniform spread so the two remain comparable; adding
locality would raise both and is a change to log, not to slip in.

**`hold` was measured across many pools, not one.** NFR-2's wording says "single
hot partition". Concentrating load on one pool is a different measurement and it is
**P3's** — it exhausts ~250 berths in seconds and then times the `SEAT_UNAVAILABLE`
path, which is a free-count read returning zero. The `real allocations` column
above exists so this claim is checkable: 91–100 % of every reported step was a
genuine allocation.

**p99 remains the noisy statistic.** p50 sat at 4 ms (`search`) and 8 ms (`hold`)
across every step and every repetition. p99 moved by tens of milliseconds between
otherwise identical runs. The knee is real; its exact position is ±1 step.

**`psp-sim` idles at 245 MiB of a 256 MiB limit** — 95.7 %. It took no load during
this run. Before C5 puts real traffic through it, that headroom needs revisiting or
an OOM kill will present as a PSP outage.

---

## Memory (NFR-11), sampled after the run

| Container | Resident | Limit |
|---|---:|---:|
| app-1 | 436.3 MiB | 512 |
| app-2 | 449.7 MiB | 512 |
| kafka | 475.9 MiB | 1024 |
| postgres | 350.2 MiB | 512 |
| psp-sim | 245.0 MiB | 256 |
| grafana | 117.1 MiB | 256 |
| prometheus | 79.9 MiB | 384 |
| redis | 21.7 MiB | 512 |
| toxiproxy | 14.0 MiB | 32 |
| nginx | 6.0 MiB | 32 |
| **Total** | **2,195.8 MiB** | 4,608 |

**PASS — 48 % of NFR-11's budget**, against 34 % at idle in Phase 0. The growth is
almost entirely the two app replicas under load.

---

## Reproduce

```bash
docker compose build app-1
TATKAL_CLOCK_OFFSET=P40D docker compose up -d --wait
java -jar ops/pool-warmup/target/pool-warmup.jar "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal localhost 6379
./loadtest/extract-targets.sh
SEARCH_RATES="250 400 550 700" HOLD_RATES="50 100 150 200" ./loadtest/calibration/run-nfr-calibration.sh
```

Seeding, if the database is empty, is step 3 of [docs/runbook.md](../runbook.md).
