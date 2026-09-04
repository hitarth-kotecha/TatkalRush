# TatkalRush

A railway seat reservation engine built to stay **correct** under extreme, bursty
write contention — the condition under which most booking systems quietly fail.

> **Status: Phase 0 complete.** The foundation is built and measured. The
> allocator itself is Phase 1 and is not written yet. See
> [What exists today](#what-exists-today) — nothing below claims otherwise.

---

## The problem

Selling a seat on a train is not decrementing a counter, and treating it as one
is not merely imprecise — it is wrong. Three properties combine to make this
materially harder than ordinary inventory.

### 1. Inventory is an interval, not a unit

A berth on a train running Delhi → Kota → Ratlam → Surat → Mumbai is not one
sellable item. It can be sold once as Delhi→Mumbai, **or simultaneously** as
Delhi→Ratlam *and* Ratlam→Mumbai. Two bookings conflict only if their segment
ranges overlap.

Each berth's occupancy is a 64-bit mask, one bit per route segment. A booking is
a bitwise test and set:

| Booking | Range | Mask | Berth after | |
|---|---|---|---|---|
| — | — | — | `0000` | empty berth |
| B1 NDLS→RTM | `[0,2)` | `0011` | `0011` | allocated |
| B2 ST→BCT | `[3,4)` | `1000` | `1011` | no overlap → allocated |
| B3 KOTA→ST | `[1,3)` | `0110` | `1011` | `0110 & 1011 = 0010` → **rejected** |
| B4 RTM→ST | `[2,3)` | `0100` | `1111` | `0100 & 1011 = 0` → allocated |
| B1 cancelled | `[0,2)` | `0011` | `1100` | `mask AND NOT 0011` |

One berth, three concurrent passengers, no overbooking. Ranges are half-open, so
`[0,2)` and `[2,4)` share a station but not a leg — which is exactly why both fit.

### 2. Demand is a Dirac spike, not a distribution

The Tatkal quota unlocks at a fixed wall-clock instant. Traffic in the
surrounding 30 seconds is orders of magnitude above baseline and concentrated on
a handful of hot partitions — popular trains, popular dates. Sharding by user, by
request, or by anything other than the contended resource does not help. **The
contention is irreducible; it can only be organised.**

### 3. The write path spans an external system

Seat allocation and payment capture have independent failure modes. A seat must
be held during payment, holds must expire, expiry must not race with a late
payment success, and a payment webhook may arrive twice, out of order, or never.

A system that handles all three is a serious artifact. A system that handles only
the third is a CRUD app with a queue in front.

---

## The claim this project has to defend

> Under a 30-second traffic spike targeting a single train's inventory, the
> system allocated seats with zero overbooking, zero double-charges and zero
> orphaned holds, using **two different concurrency strategies whose trade-offs
> were measured rather than assumed**.

Two strategies, behind one interface, benchmarked head to head under identical
load:

- **Strategy A** — atomic allocation inside Redis as a Lua script.
- **Strategy B** — a Kafka-partitioned single writer with a write-ahead log,
  checkpointing, replay, and producer-epoch fencing.

The allocation algorithm is **specified once and implemented twice** — Java for
B, Lua for A. They cannot share code: Strategy A executes inside the Redis
process, and the atomicity that makes it correct depends on the algorithm never
leaving Redis mid-execution. Equivalence is therefore a *tested property*, proven
step-for-step by a differential test, not a shared-code property. That test is
what makes the comparison a controlled experiment rather than a comparison of two
different programs.

---

## Correctness is enforced by the database, not by hope

```sql
CONSTRAINT no_overlapping_allocations EXCLUDE USING gist (
    schedule_id WITH =,
    berth_id    WITH =,
    seg_range   WITH &&
)
```

`EXCLUDE` generalises `UNIQUE`: where `UNIQUE` says "no two rows are equal on
these columns", this says "no two rows are *related* by these operators" — same
schedule, same berth, and segment ranges that **overlap**.

Application-level validation is precisely what fails under this kind of
concurrency: two threads can both read "berth 7 is free" before either writes.
A constraint is evaluated by the one component that sees every writer.

If both allocators are correct, this constraint can **never** fire. A firing is
therefore not an edge case to handle gracefully — it is a detector announcing
that an allocator bug shipped, at a moment when the customer's money has already
been captured. It auto-refunds with a distinguishable reason code and **fails the
run**.

Alongside it, twelve machine-checked invariants run after every load and chaos
run. A passing load test with a failing invariant is a failed build.

---

## Architecture

A **modular monolith** deployed as N stateless replicas. That is a deliberate
choice, and being able to explain why it was *not* split is worth more than
having split it.

The contention in this system is on a **shared resource**, not on a service
boundary. Splitting search, booking, payment and charting into separate
deployables would add network hops, distributed transactions and operational
surface without reducing contention on a single train's berth inventory by one
iota. The genuinely hard problem — serialising conflicting writes to overlapping
segment ranges — would be unchanged. Distributing it first is cargo-culting.

What *does* help is partitioning inventory and giving each partition a single
writer. That is a data-plane decision, and it lives inside the monolith.

```
                        ┌──────────────┐
                        │  React UI    │
                        └──────┬───────┘
                        ┌──────▼───────┐
                        │    nginx     │  round-robin, 2 upstreams
                        └──┬────────┬──┘
                 ┌─────────▼──┐  ┌──▼─────────┐
                 │   app-1    │  │   app-2    │  Spring Boot, virtual threads
                 └──┬───┬───┬─┘  └─┬───┬───┬──┘
        ┌───────────┘   │   └──────┘   │   └──────────┐
        ▼               ▼              ▼              ▼
  ┌──────────┐   ┌───────────┐   ┌──────────┐   ┌──────────┐
  │ Postgres │   │   Redis   │   │  Kafka   │   │ psp-sim  │
  │ (truth)  │   │ (holds,   │   │(commands,│   │(latency, │
  │          │   │  queue)   │   │ events,  │   │ failures)│
  └──────────┘   └───────────┘   │  WAL)    │   └──────────┘
        │                        └────┬─────┘
        └────────► Prometheus ◄───────┘ ──► Grafana
                   Toxiproxy (chaos, in front of PG + Redis)
```

Module boundaries point inward only and are enforced **at compile time**: a
module that does not declare another cannot resolve its classes at all, so an
inward-pointing dependency is a compile error rather than an assertion that runs
later. ArchUnit catches what compilation cannot — chiefly a framework annotation
reaching the domain through a transitive dependency.

`domain/` has zero framework dependencies and is the reference specification of
the allocation algorithm.

---

## What exists today

Phase 0 is the foundation, and it is complete and measured. **The booking path
is not built yet.**

| | Status |
|---|---|
| Maven reactor, compile-enforced boundaries | ✅ built |
| Docker Compose stack, digest-pinned | ✅ built |
| Postgres schema + exclusion constraint | ✅ built |
| Deterministic seed generator | ✅ built |
| Correlation ID, structured logging, metrics | ✅ built |
| CI: build, stack, decision-log validation | ✅ built |
| **Segment-mask allocator, Strategy A** | Phase 1 |
| **Booking lifecycle, payment, PNR** | Phase 1 |
| **Twelve invariant checks** | Phase 1 |
| **Strategy B, chaos suite, comparison** | Phase 2 |
| **RAC/WL, chart preparation** | Phase 3a |
| **Admission control, React dashboard** | Phase 3b |

### Phase 0 acceptance

| Gate | Result |
|---|---|
| Stack healthy from cold | **32 s** (budget 120 s) |
| Deterministic seed | **21.3 s** (budget 60 s), 291,120 bookable berths |
| Schema + exclusion constraint | 20 tables; overlap rejected, complementary legs both allocated |
| Boundary enforcement | verified by deliberate violation — compile error *and* enforcer |
| Decision log | 30 entries, validated in CI |
| Toolchain spike | passed |
| Memory | **1,565 MiB** of a 4,608 MiB budget (34 %) |

---

## Measurements

Every number here carries its hardware, its JDK build, and the fact that the
load generator shared the machine. That is a project requirement, not a courtesy:
an inflated number that cannot be reproduced is worse than no number.

**Hardware:** Intel i5-10300H (4 cores / 8 threads), 7.91 GB RAM, Windows 10 +
WSL2 · **JDK:** Temurin 25.0.4+7 · **k6 v2.2.0, co-located.**

| Measurement | Result |
|---|---|
| HTTP ceiling, no I/O in path | **750 req/s** within 50 ms p99 |
| HTTP ceiling, one backend round trip | **500 req/s** within 50 ms p99 |
| Stack resident memory, idle | 1,565 MiB |
| Cold start to all-healthy | 32 s |

**These are upper bounds, not throughput results.** They are what the machine can
do with *no domain work in the path*. Real endpoint throughput will be a fraction
of them and is not yet measured. Full record and threats to validity:
[`docs/benchmarks/000-calibration.md`](docs/benchmarks/000-calibration.md).

The original design targeted a 16 GB laptop. The build machine has 7.9 GB, so the
performance requirements were recalibrated to measured reality rather than
halved by guesswork — memory is a budget you divide, throughput is an outcome you
measure. The reasoning is [DD-019](docs/design-decisions.md#dd-019).

---

## Deliberate limitations

Stated here rather than discovered later.

- **Kafka runs as a single broker with `replication.factor=1`.** This is an
  accepted single point of failure, taken so the whole stack fits on one laptop.
  A chaos scenario restarts the broker specifically to show what the system does
  when it blinks.
- **The load generator shares the machine** with the system under test. Headroom
  on dedicated infrastructure would be higher. No estimate of how much higher is
  offered.
- **The payment provider is simulated**, with controllable latency and failure
  rates. No real PSP integration.
- **Auth is a stub JWT issuer.** No accounts, no KYC.
- **Failed benchmark runs stay in the repository.** The history of what broke and
  how it was fixed is the most interesting document here; deleting it is the
  fastest way to make a project look fabricated.

---

## Running it

Requires Docker, JDK 25 and Maven.

```bash
docker compose up -d --wait     # full stack, ~32 s
mvn -B clean verify             # 102 tests, ~1.5 min (needs Docker for Testcontainers)
```

The stack fits in 4.5 GB. On WSL2, copy [`ops/wsl/.wslconfig`](ops/wsl/.wslconfig)
to `%USERPROFILE%\.wslconfig` and run `wsl --shutdown` first, or the VM claims
half your RAM and no measurement is reproducible.

| Service | URL |
|---|---|
| Application (via nginx) | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

Reproduce the calibration:

```bash
./ops/docker/measure-memory.sh 300 30
./loadtest/calibration/run-calibration.sh
```

---

## Why the decision log matters

[`docs/design-decisions.md`](docs/design-decisions.md) is 30 entries and counting.
Every one carries the problem, the decision, **at least two rejected alternatives
with specific reasons**, the consequences including what got worse, and a named,
observable condition that would reverse it. A decision you cannot falsify was not
a decision.

It is append-only. Reversals supersede rather than delete, because the reversals
are the most interesting part. CI validates the structure —
[`ops/docs/validate-decision-log.py`](ops/docs/validate-decision-log.py) — though
no script can check whether the reasoning is any good.

This exists because the point of the project is not the code. It is being able to
answer *"why did you build it that way?"* with a measurement instead of an
opinion, and *"what breaks?"* with a chaos report instead of a guess.

---

## Documents

| | |
|---|---|
| [Software Design Document](tatkal-rush-sdd-v1.2.md) | The binding specification |
| [Design decision log](docs/design-decisions.md) | Why, and what was rejected |
| [Benchmarks](docs/benchmarks/) | Every run, including the failures |
