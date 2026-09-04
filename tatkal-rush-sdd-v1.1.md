# TatkalRush — Software Design Document

**Version:** 1.1
**Status:** Ready for agent decomposition
**Changes since 1.0:** Kafka replaces Redpanda as the log (§8.3, §8.4), with partition count and single-broker SPOF made explicit; NFR-10 and NFR-11 retuned for Kafka's footprint; mandatory design decision log added (§0, §21.1, DOC-1…DOC-9) with acceptance criteria in every phase.
**Author:** Hitarth
**Target consumer:** SpecForge (Architect → Coder → Reviewer → Test agent pipeline)

---

## 0. How to consume this document

This is a narrative SDD, not a ticket backlog. Agents in the SpecForge pipeline should treat it as follows.

**Architect agent.** Sections 3–14 are the binding design. Decompose into modules using the boundaries in §8.2 and the delivery phases in §20. Do not restructure module boundaries; they were chosen deliberately and the rationale is recorded inline.

**Coder agents.** Every requirement carries a stable ID (`FR-*`, `NFR-*`, `INV-*`, `API-*`). Reference the ID in commit messages and in code comments at the point of implementation. Where this document specifies an algorithm (§5, §9, Appendix A), implement it as written; where it specifies only behaviour, you choose the implementation.

**Reviewer agent.** §14 (invariants) and §20 (acceptance criteria) are the review checklist. A change that cannot be traced to a requirement ID is out of scope and should be rejected.

**Test agent.** §18 and §19 define the test strategy and load profiles. Invariants in §14 must have executable checks; a passing load test with a failing invariant is a failed build.

**Rule for all agents:** do not invent requirements. Anything genuinely underspecified goes into the Open Questions register (§23) and is escalated, not guessed. The one exception is §17 (frontend), which is intentionally loose on visual design.

**Second rule for all agents:** every non-trivial choice you make must be written down, with its rejected alternatives, in the design decision log (§21.1). This document specifies *what* to build and, where the choice was already made, *why*. It does not and cannot specify the hundreds of smaller decisions you will make while building — which data structure, which retry policy, which index, which failure to swallow and which to propagate. Those decisions are the actual engineering, and an undocumented one is indistinguishable from an accident.

This obligation is load-bearing for the project's purpose. The reason this codebase is worth putting on a resume is that its author can explain every design choice under questioning. If the agents make those choices and no one records the reasoning, the artifact is a codebase nobody can defend — which is worse than no project at all. Requirements carrying the `DOC-*` prefix govern this and are as binding as any `FR-*`.

---

## 1. Purpose and framing

TatkalRush is a railway seat reservation system modelled on Indian Railways' Tatkal scheme. It exists to demonstrate the design and implementation of a system that stays **correct** under extreme, bursty write contention — the condition where most booking systems quietly fail.

The engineering claim this project must be able to defend in an interview:

> Under a 30-second traffic spike targeting a single train's inventory, the system allocated seats with zero overbooking, zero double-charges and zero orphaned holds, using two different concurrency strategies whose trade-offs were measured rather than assumed.

Everything in this document serves that claim. Features that do not serve it are deliberately excluded (§4).

---

## 2. Why this problem is hard

Three properties combine to make railway booking materially harder than the usual "decrement a counter" inventory problem.

**2.1 Inventory is an interval, not a unit.** A berth on a train running Delhi → Kota → Ratlam → Surat → Mumbai is not one sellable item. It can be sold once as Delhi→Mumbai, or simultaneously as Delhi→Ratlam *and* Ratlam→Mumbai. Availability for a query is therefore a function of the requested *segment range*, and two bookings conflict only if their ranges overlap. A naive `available_seats` counter is not merely imprecise here — it is wrong.

**2.2 Demand is a Dirac spike, not a distribution.** Tatkal quota unlocks at a fixed wall-clock instant. Traffic in the surrounding 30 seconds is orders of magnitude above baseline, and it is concentrated on a handful of hot partitions (popular trains on popular dates). Sharding by user, by request, or by anything other than the contended resource does not help. The contention is irreducible; it can only be organised.

**2.3 The write path spans an external system.** Seat allocation and payment capture are separate operations with independent failure modes. A seat must be held during payment, holds must expire, expiry must not race with a late payment success, and a payment webhook may arrive twice, out of order, or never.

A system that handles all three is a legitimate senior-level artifact. A system that handles only the third is a CRUD app with a queue in front.

---

## 3. Goals

- **G-1** Correct segment-wise seat allocation under high concurrent write load.
- **G-2** Two interchangeable concurrency strategies behind one interface, benchmarked head to head under identical load.
- **G-3** An end-to-end booking lifecycle: search → hold → pay → confirm → PNR → cancel → refund.
- **G-4** Machine-verified invariants after every load and chaos run.
- **G-5** A reproducible load and chaos harness that runs on a single 16 GB developer laptop.
- **G-6** Observability sufficient to explain *why* a strategy performs the way it does, not just that it does.

## 4. Non-goals

Explicitly out of scope. Agents must not implement these.

- **NG-1** Real payment gateway integration. A simulated PSP with controllable latency and failure rates is in scope; Razorpay/Stripe is not.
- **NG-2** User accounts, profiles, password reset, KYC, ID verification. Auth is a stub JWT issuer (§16).
- **NG-3** Concession categories, senior citizen quotas, ladies quota, defence quota. Only GENERAL and TATKAL pools exist.
- **NG-4** The full IRCTC refund slab matrix. A simplified three-tier rule is specified in FR-38.
- **NG-5** Real train timetable data ingestion. Seed data is synthetic and generated (§10.6).
- **NG-6** Kubernetes, service mesh, cloud deployment, CI/CD to a live environment. Docker Compose is the deployment target.
- **NG-7** Multi-region, cross-datacenter replication, or geo-distributed consensus.
- **NG-8** Mobile applications.
- **NG-9** Microservice decomposition. See §8.1 for the rationale.

---

## 5. Domain model

### 5.1 Glossary

| Term | Meaning in this system |
|---|---|
| Station | A stop, identified by a code (e.g. `NDLS`) |
| Train | A named service with a fixed ordered route |
| Stop | A `(train, station, sequence, arrival, departure, distance_km)` tuple |
| Segment | The stretch between two consecutive stops. A route with `N` stops has `N-1` segments, indexed `0..N-2` |
| Segment range | `[from_seq, to_seq)` — the half-open set of segments a journey occupies |
| Journey date | The calendar date on which the train departs its origin |
| Schedule | A `(train, journey_date)` instance. The unit of inventory |
| Class | Travel class: `SL`, `3A`, `2A`, `1A`, `CC` |
| Coach | A physical carriage belonging to one class |
| Berth | A numbered sleeping/seating position in a coach. The atomic allocatable unit |
| Quota pool | A reserved subset of berths for a booking scheme: `GENERAL` or `TATKAL` |
| Hold | A time-limited exclusive claim on a berth range, pending payment |
| PNR | Passenger Name Record — the 10-digit booking identifier |
| CNF | Confirmed booking with an assigned berth |
| RAC | Reservation Against Cancellation — two passengers share one berth |
| WL | Waitlist — no berth, position in a queue |
| Chart preparation | The T-minus-4-hours batch that finalises allocations and promotes RAC/WL |

### 5.2 The segment bitmask

This is the central data structure of the system. Agents must implement it as described.

A berth's occupancy across a schedule is represented as a **bitmask over segments**. Bit `i` is set if the berth is occupied on segment `i`.

```
Route:    NDLS --0-- KOTA --1-- RTM --2-- ST --3-- BCT
Segments:       0          1         2        3

Booking A: NDLS → RTM   = segments {0,1} = 0b0011
Booking B: ST   → BCT   = segment  {3}   = 0b1000
Berth mask after both   =                  0b1011

Request C: KOTA → ST    = segments {1,2} = 0b0110
  0b1011 & 0b0110 = 0b0010 ≠ 0  →  CONFLICT, berth unavailable
Request D: RTM  → ST    = segment  {2}   = 0b0100
  0b1011 & 0b0100 = 0        →  AVAILABLE
```

**Rules:**
- **FR-1** A berth is available for a request iff `berthMask & requestMask == 0`.
- **FR-2** Allocation is `berthMask |= requestMask`. Release is `berthMask &= ~requestMask`.
- **FR-3** The mask is a Java `long`, constraining routes to a maximum of 64 segments (65 stops). This exceeds any real Indian Railways route. Seed data must validate this bound; a route exceeding it is a data error, not a runtime concern.
- **FR-4** `requestMask` for `[from_seq, to_seq)` is `((1L << to_seq) - 1) ^ ((1L << from_seq) - 1)`.

This makes the conflict check a single machine instruction, which matters when it sits on the hot path of every booking attempt during a spike.

### 5.3 Berth allocation preferences

- **FR-5** Within a class, allocate from the berth with the **lowest ordinal that satisfies FR-1**. Deterministic allocation makes tests reproducible and makes the two strategies in §9 directly comparable.
- **FR-6** A booking of `k` passengers must allocate `k` berths in a single atomic operation. Partial allocation is forbidden; either all `k` are held or none are.
- **FR-7** Group bookings do not require adjacent berths. (Simplification; recorded so the Reviewer agent does not flag it.)

### 5.4 Quota pools

- **FR-8** Each `(schedule, class)` has two pools: `GENERAL` and `TATKAL`. Pool membership is assigned at schedule creation by partitioning the berth set — a berth belongs to exactly one pool.
- **FR-9** `TATKAL` pool size is `ceil(0.10 × class_capacity)`, minimum 1 berth.
- **FR-10** The `TATKAL` pool is locked until the Tatkal window opens (FR-24). Requests against a locked pool return `QUOTA_LOCKED`.
- **FR-11** Unsold `TATKAL` berths are **not** released into `GENERAL` before chart preparation.

---

## 6. Functional requirements — booking lifecycle

### 6.1 Search and availability

- **FR-12** `API-1` returns trains serving a `(from, to, date)` query, with per-class availability counts.
- **FR-13** Availability count for a segment range is the number of berths in the pool whose mask does not conflict. Computing this exactly on every search is too expensive at spike load; the system maintains a **per-segment free-count array** of `N-1` integers per `(schedule, class, pool)` in Redis, and reports `min(free_count[i]) for i in requestMask` as an **upper bound** estimate.
- **FR-14** Search results are explicitly labelled as approximate. Exactness is only guaranteed at hold time. This is the correct trade-off and mirrors real systems; the Reviewer agent should treat any attempt to make search strongly consistent as a defect.
- **FR-15** Search responses are cached in Redis for 2 seconds, keyed by `(train, date, from, to, class)`. During a Tatkal spike this collapses read amplification by orders of magnitude.

### 6.2 Hold

- **FR-16** `API-4` atomically allocates `k` berths and creates a `HOLD` record with a TTL.
- **FR-17** Hold TTL is **120 seconds** (configurable via `tatkal.hold.ttl`). Real IRCTC allows longer; 120s keeps load-test cycles short.
- **FR-18** Holds must be released on expiry, restoring the berth masks. Expiry is driven by a reaper (§13.2), not by TTL alone — a Redis TTL removes the hold record but cannot itself clear the mask bits.
- **FR-19** Every hold request carries an `Idempotency-Key` header. Replay of the same key within 10 minutes returns the original response without allocating again.
- **FR-20** Holds are rejected when the caller has more than 3 active holds.

### 6.3 Payment

- **FR-21** `API-5` initiates payment against a held booking. The system calls the simulated PSP (§12) and transitions the booking to `PAYMENT_PENDING`.
- **FR-22** `API-6` receives PSP webhooks. Webhooks are HMAC-signed, may arrive **more than once**, **out of order**, or **not at all**. Handling must be idempotent on `(payment_id, event_type)`.
- **FR-23** A reconciliation job polls the PSP every 30 seconds for payments in `PAYMENT_PENDING` older than 60 seconds, covering the never-arrives case.
- **FR-24** If payment succeeds after the hold expired and the berth was reallocated, the payment is **auto-refunded** and the booking moves to `FAILED_REFUNDED`. This race must be tested explicitly (T-C4).

### 6.4 Confirmation and PNR

- **FR-25** On payment success within the hold window, the booking transitions `HELD → CONFIRMED`, hold records are converted to permanent `seat_allocations`, and a PNR is issued.
- **FR-26** PNR is a 10-digit string derived from a Postgres sequence plus a Luhn check digit. Random generation with collision retry is forbidden — it degrades under exactly the load this project is about.
- **FR-27** Booking state machine:

```
     ┌─────────┐  hold expires / released   ┌──────────┐
     │  HELD   │──────────────────────────▶ │ EXPIRED  │
     └────┬────┘                            └──────────┘
          │ initiate payment
          ▼
  ┌───────────────┐  psp failure   ┌────────────┐
  │PAYMENT_PENDING│───────────────▶│   FAILED   │
  └───────┬───────┘                └────────────┘
          │ psp success
          ├──────────── hold still valid ────▶ ┌───────────┐
          │                                    │ CONFIRMED │
          │                                    └─────┬─────┘
          │                                          │ cancel
          └──── hold already expired ─┐              ▼
                                      │        ┌───────────┐
                              ┌───────▼──────┐ │ CANCELLED │
                              │FAILED_REFUNDED│ └───────────┘
                              └──────────────┘
```

State transitions are the single source of truth for the Reviewer agent. Any transition not on this diagram is a defect.

### 6.5 Tatkal window

- **FR-28** Each `(schedule, class)` has a Tatkal open instant: **10:00:00 IST on D-1** for AC classes (`1A`,`2A`,`3A`,`CC`), **11:00:00 IST on D-1** for `SL`.
- **FR-29** Before the open instant, hold requests against the `TATKAL` pool return `QUOTA_LOCKED` with the opening time in the response.
- **FR-30** The unlock must not require a scheduled job to "flip a switch" — it is a pure function of clock time, evaluated per request. A job-based unlock introduces a window where the job has not yet run and creates an artificial thundering herd on the job itself.
- **FR-31** System time is injected via a `Clock` bean so load tests can simulate the window without waiting for 10 AM. This is mandatory; a test suite that cannot control time cannot test this system.

### 6.6 Admission control (virtual waiting room)

- **FR-32** When the instantaneous request rate for a `(schedule, class)` partition exceeds `admission.threshold_rps`, the system switches that partition into **queued mode**.
- **FR-33** In queued mode, a booking attempt receives a queue token instead of a hold. `API-2` issues tokens; `API-3` returns position and estimated wait.
- **FR-34** Tokens are held in a Redis sorted set scored by issue timestamp — strict FIFO per partition.
- **FR-35** An admission controller admits tokens at a rate derived from remaining inventory: `admit_rate = max(1, remaining_berths / expected_conversion_time_s)`, recomputed every second. Admitting far more users than there are seats is the failure mode this component exists to prevent.
- **FR-36** An admitted token grants a 60-second window to complete a hold. Unused windows are reclaimed.
- **FR-37** Queue position is streamed to the client via SSE (`API-3` supports both polling and SSE).

### 6.7 RAC, waitlist, chart preparation

- **FR-38** After the `CNF` berths of a class are exhausted for the requested range, requests are offered `RAC`. RAC capacity is `2 × side_lower_berth_count` per class, capped at 10% of class capacity.
- **FR-39** After RAC is exhausted, requests are offered `WL`, capped at 25% of class capacity. Beyond that, `QUOTA_EXHAUSTED`.
- **FR-40** RAC and WL bookings are still paid bookings with PNRs; they simply carry no berth assignment.
- **FR-41** On cancellation of a `CNF` booking, the freed berth range triggers promotion: the oldest RAC entry whose range fits is promoted to CNF, then the oldest WL entry is promoted to RAC. Promotion is transactional and emits a `BookingPromoted` event.
- **FR-42** Chart preparation runs at T-4h from origin departure (`API-8` triggers it manually for demos). It: (a) finalises all allocations, (b) runs promotion to exhaustion, (c) cancels remaining WL bookings with full refund, (d) marks the schedule `CHARTED`. After charting, no new bookings are accepted.

### 6.8 Cancellation and refund

- **FR-43** `API-7` cancels a booking, releases its berth range, triggers promotion (FR-41), and issues a refund.
- **FR-44** Simplified refund rule — three tiers by time before departure:

| Window | Refund |
|---|---|
| > 48 h | 90% of fare |
| 12–48 h | 50% of fare |
| < 12 h | 0% |

- **FR-45** **Confirmed TATKAL bookings receive no refund on cancellation**, regardless of window. This is a real IRCTC rule and one of the "interesting" ones worth keeping.
- **FR-46** WL bookings cancelled by chart preparation receive a 100% refund, overriding FR-44.
- **FR-47** Every refund writes a `refunds` row and a ledger entry. `INV-7` checks the ledger balances.

---

## 7. Non-functional requirements

Numbers are calibrated to a single 16 GB laptop running the full stack **plus** the load generator. They are deliberately modest and must be reported alongside the hardware and the co-location caveat (§19.4). An inflated number that cannot be reproduced is worse than no number.

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Sustained mixed-workload throughput (90% read / 10% write) | ≥ 2,000 req/s |
| NFR-2 | Peak spike throughput, 30 s window, single hot partition | ≥ 5,000 req/s accepted-or-queued |
| NFR-3 | Hold endpoint latency, p99, at NFR-1 load | ≤ 150 ms |
| NFR-4 | Hold endpoint latency, p99, at NFR-2 spike | ≤ 800 ms |
| NFR-5 | Search endpoint latency, p99, at NFR-1 load | ≤ 50 ms |
| NFR-6 | Seat allocation core operation, p99 (excludes I/O) | ≤ 5 ms |
| NFR-7 | Error rate excluding legitimate `SEAT_UNAVAILABLE` / `QUOTA_EXHAUSTED` | ≤ 0.1% |
| NFR-8 | Recovery time after partition-owner crash (Strategy B) | ≤ 10 s |
| NFR-9 | Invariant violations, all runs | **0, non-negotiable** |
| NFR-10 | Cold start of full stack via `docker compose up` | ≤ 120 s |
| NFR-11 | Total resident memory of the running stack | ≤ 7 GB |

---

## 8. Architecture

### 8.1 Architectural stance and rationale

**TatkalRush is a modular monolith deployed as N stateless replicas.**

This is a deliberate choice and the Architect agent must not override it. The rationale, which should also appear in the project README because it is an interview asset:

The contention in this system is on a *shared resource*, not on a *service boundary*. Splitting search, booking, payment and charting into separate deployables would add network hops, distributed transactions and operational surface without reducing contention on a single train's berth inventory by one iota. The genuinely hard problem — serialising conflicting writes to overlapping segment ranges — is unchanged. Distributing it first is cargo-culting.

What *does* help is partitioning inventory and giving each partition a single writer (§9.3). That is a data-plane decision, not a deployment-topology decision, and it is implemented within the monolith.

Module boundaries are enforced at compile time (§8.2) so the system *could* be split later. Being able to explain why you chose not to split it is more valuable than having split it.

### 8.2 Module boundaries

Gradle multi-module build. Dependencies point inward only; violations fail the build (ArchUnit, `AC-0.4`).

```
tatkal-rush/
├── domain/              ← pure domain. Zero framework dependencies.
│   ├── inventory/       segment masks, berth allocation, quota pools
│   ├── booking/         booking aggregate, state machine, PNR
│   ├── waitlist/        RAC/WL entities, promotion rules
│   └── pricing/         fare, refund tier calculation
├── application/         ← use cases, ports, orchestration
│   ├── ports/           SeatAllocator, PaymentGateway, EventPublisher (interfaces)
│   └── usecases/        HoldSeats, InitiatePayment, ConfirmBooking, Cancel, PrepareChart
├── adapters/
│   ├── persistence/     JPA/JDBC, Flyway migrations, outbox
│   ├── allocator-redis/ Strategy A (§9.2)
│   ├── allocator-swp/   Strategy B (§9.3)
│   ├── messaging/       Kafka producers/consumers
│   ├── payment-sim/     simulated PSP client
│   └── web/             REST controllers, SSE, problem+json mapping
├── admission/           ← virtual waiting room, rate governor
├── ops/
│   ├── invariant-checker/
│   └── seed/            synthetic data generator
├── loadtest/            k6 scripts, chaos scripts, report generator
└── ui/                  React dashboard (§17)
```

**Critical constraint:** `domain/inventory` must contain the segment-mask allocation logic in framework-free code, with **no** knowledge of Redis, Kafka or Postgres. Both allocator strategies call into it. This is what makes the head-to-head comparison in §9.4 meaningful — the allocation algorithm is identical and only the concurrency mechanism differs.

### 8.3 Runtime topology

```
                        ┌──────────────┐
                        │ React UI     │
                        └──────┬───────┘
                               │
                        ┌──────▼───────┐
                        │    nginx     │  round-robin, 2 upstreams
                        └──┬────────┬──┘
                 ┌─────────▼──┐  ┌──▼─────────┐
                 │  app-1     │  │  app-2     │  Spring Boot, virtual threads
                 └──┬───┬───┬─┘  └─┬───┬───┬──┘
        ┌───────────┘   │   └──────┘   │   └──────────┐
        ▼               ▼              ▼              ▼
  ┌──────────┐   ┌───────────┐   ┌──────────┐   ┌──────────┐
  │ Postgres │   │   Redis   │   │  Kafka   │   │ psp-sim  │
  │ (truth)  │   │(holds,    │   │(commands,│   │(latency, │
  │          │   │ queue,    │   │ events,  │   │ failures)│
  │          │   │ cache)    │   │ WAL)     │   │          │
  └──────────┘   └───────────┘   └──────────┘   └──────────┘
        │                              │
        └──────────► Prometheus ◄───────┘ ──► Grafana
                     Toxiproxy (chaos, in front of PG + Redis)
```

**Kafka configuration.** Single broker in **KRaft mode** — no ZooKeeper. Kafka is the heaviest component in the stack and the main pressure on NFR-11, so it is tuned for the laptop budget rather than run at defaults:

- `KAFKA_HEAP_OPTS=-Xms1g -Xmx1g`
- `num.partitions=12` (default for `booking-commands`; see below)
- `log.retention.hours=24`, `log.segment.bytes=64MB` — the WAL only needs to outlive a replay window
- `min.insync.replicas=1` with `replication.factor=1`, because there is one broker. **This is a deliberate single-point-of-failure**, accepted so the whole stack fits on one machine, and it must be called out in the ADR and the README rather than quietly assumed away. Chaos scenario C4 restarts the broker precisely to show what the system does when that SPOF blinks.

**Partition count is a real design decision, not a default.** `booking-commands` partition count is the upper bound on Strategy B's write parallelism, since one partition means one owner thread. Twelve partitions across two replicas gives six owned partitions each — enough that the hot-partition profile (P3) still concentrates load on one owner, which is the point of that test. Agents must not raise this to "a big number"; the ceiling on a laptop is CPU cores, and over-partitioning inflates rebalance time and hurts NFR-8.

### 8.4 Technology stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 25 (LTS) | Virtual threads, structured concurrency, scoped values |
| Framework | Spring Boot 3.4+ | Virtual-thread executor enabled |
| Database | PostgreSQL 16 | `btree_gist` extension required (§10.2) |
| Cache / holds / queue | Redis 7 | Lua scripting for atomicity |
| Log / commands | Apache Kafka 3.7+ | KRaft mode, single broker, 1 GB heap (§8.3) |
| Migrations | Flyway | |
| Metrics | Micrometer → Prometheus → Grafana | |
| Tracing | OpenTelemetry → Jaeger | **Optional profile**; off by default for memory |
| Load | k6 | |
| Chaos | Toxiproxy + `docker kill` | |
| Test | JUnit 5, Testcontainers, jqwik (property-based) | |
| Frontend | React 18 + Vite + TypeScript | §17 |

### 8.5 Concurrency model

- Spring MVC on **virtual threads** (`spring.threads.virtual.enabled=true`). This matters concretely for Strategy B, where an HTTP handler blocks awaiting a reply from a partition owner over Kafka. With platform threads, thousands of concurrent blocked handlers would exhaust the pool; with virtual threads the blocking style is affordable and the code stays readable.
- **Structured concurrency** (`StructuredTaskScope`) for search fan-out across trains, so a slow train's availability lookup cannot stall the whole response and cancellation propagates cleanly.
- **Scoped values** for request-scoped context (correlation ID, admission token) instead of `ThreadLocal`, which does not behave well with virtual threads at this scale.

---

## 9. Concurrency strategies — the centerpiece

### 9.1 The port

```java
public interface SeatAllocator {
    AllocationResult allocate(AllocationRequest request);
    void release(HoldId holdId);
    ConfirmResult confirm(HoldId holdId, BookingId bookingId);
    AvailabilitySnapshot availability(ScheduleId id, TravelClass cls, SegmentRange range);
}
```

Selected by `tatkal.allocator.strategy = redis-lua | single-writer`. Both implementations must pass the identical contract test suite (`AC-1.6`) and the identical load profiles.

### 9.2 Strategy A — Redis Lua atomic holds

**Model.** Berth masks for a `(schedule, class, pool)` are packed into a single Redis string: `berth_count × 8` bytes, one `long` per berth. A Lua script performs find-first-fit and set-bits atomically — Redis executes Lua single-threaded, so the read-modify-write cannot interleave.

**Allocation script (`allocate.lua`) outline:**

```
KEYS[1] = masks:{schedule}:{class}:{pool}     -- packed berth masks
KEYS[2] = holds:{schedule}:{class}:{pool}     -- ZSET holdId -> expiry epoch ms
KEYS[3] = freecount:{schedule}:{class}:{pool} -- packed per-segment free counts
ARGV    = requestMask, passengerCount, holdId, ttlMs, nowMs

1. Reap: ZRANGEBYSCORE KEYS[2] -inf nowMs
   for each expired hold: clear its bits from KEYS[1], increment free counts, ZREM
2. Scan berths in ordinal order; collect first `passengerCount` berths where
   mask & requestMask == 0
3. If fewer than passengerCount found: return {status="UNAVAILABLE"}
4. Set bits on the chosen berths; decrement free counts for segments in requestMask
5. ZADD KEYS[2] (nowMs + ttlMs) holdId; HSET hold detail (berths, mask)
6. Return {status="OK", berths={...}}
```

Reaping expired holds *inside* the allocation script (step 1) is the key design decision: it makes expiry lazy and self-healing, so a stalled background reaper cannot cause seats to be permanently lost. A background reaper still runs every 5 s (§13.2) to release seats during idle periods, but correctness does not depend on it.

**Durability.** Redis is a *cache with authority during the hold window only*. Confirmed allocations are written to Postgres inside the confirmation transaction. On Redis loss, masks are rebuilt from Postgres (§13.4); in-flight holds are lost, which is acceptable and must be tested (chaos scenario C2).

**Expected characteristics.** One network round trip per allocation; contention resolved inside Redis; Redis becomes the single-threaded bottleneck for the hottest partition. Predict this before measuring it, then check the prediction — that comparison is worth more in an interview than the raw numbers.

### 9.3 Strategy B — partitioned single-writer

**Model.** Eliminate contention by construction rather than resolving it.

Inventory is partitioned by key `{trainId}:{journeyDate}:{class}`. Booking commands are published to the Kafka topic `booking-commands` keyed by that partition key. Application replicas form a consumer group; **Kafka partition assignment is the ownership protocol**. The owner of a partition holds that partition's berth masks in a plain `long[]` in heap memory and applies commands **single-threaded**. No locks, no CAS, no transactions on the hot path — the allocation is an array scan and a bitwise OR.

**Request/response over the log:**

```
HTTP handler (any replica, virtual thread)
  → publish AllocateCommand{correlationId, replyPartition, ...} to booking-commands
  → register CompletableFuture in a pending map
  → block on future with timeout (viable because virtual threads)

Partition owner (single consumer thread)
  → apply to in-memory masks
  → append AllocationEvent to booking-events   ← this is the WAL
  → publish AllocateReply{correlationId, ...} to booking-replies[replyPartition]

Originating replica's reply consumer
  → complete the future → HTTP response
```

**Durability and recovery.**
- `booking-events` is the write-ahead log and the source of truth for recovery. It is produced with `acks=all`.
- The owner checkpoints `(partition, offset, mask snapshot)` to Postgres every 5 seconds or 1,000 events.
- On rebalance, the new owner loads the latest checkpoint and replays `booking-events` from the checkpoint offset before serving. Commands received during replay are buffered, not rejected.
- **Requirement NFR-8:** replay-to-serving must complete within 10 s for a partition with 100k events.
- During replay the partition returns `RETRY_LATER` (HTTP 503 + `Retry-After`) rather than a wrong answer.

**Ordering and idempotency.** Kafka guarantees per-partition ordering, so command order is total per partition. Every command carries a client-generated `commandId`; the owner keeps a bounded LRU of applied `commandId`s to make replay idempotent (a replayed event must not double-apply a mask).

**The split-brain question.** During a rebalance two replicas could briefly believe they own a partition. Mitigation: the owner includes its consumer generation ID on every event; the projection layer rejects events from a stale generation. Agents must implement this — it is the failure mode a senior interviewer will probe first.

### 9.4 Comparison methodology

This section defines G-2 and is the primary deliverable of Phase 2.

Both strategies run identical k6 profiles (§19), on identical seed data, with an identical invariant check afterwards. Report per strategy:

- Throughput at NFR-1 and NFR-2 profiles
- Latency p50/p95/p99/p99.9 for hold
- Behaviour on the hot-partition profile (P3) — the discriminating test
- Recovery time and lost-work count under chaos scenarios C1–C5
- Memory and CPU profile
- Lines of code and cyclomatic complexity of each adapter (honest complexity accounting)

The written conclusion must state **when each strategy is the right choice**, not declare a winner. A spec that assumes Strategy B wins has prejudged the experiment; if Redis-Lua wins on this hardware, that is the finding and it gets reported.

---

## 10. Data model

### 10.1 Core tables

```sql
stations(id, code UNIQUE, name)
trains(id, number UNIQUE, name, origin_station_id, dest_station_id)
train_stops(id, train_id, station_id, seq, arr_time, dep_time, distance_km,
            UNIQUE(train_id, seq))
coaches(id, train_id, code, travel_class, berth_count)
berths(id, coach_id, ordinal, berth_type, UNIQUE(coach_id, ordinal))

schedules(id, train_id, journey_date, status, chart_prepared_at,
          UNIQUE(train_id, journey_date))
  -- status: OPEN | CHARTED | DEPARTED | CANCELLED

quota_pools(id, schedule_id, travel_class, quota_type, total_berths, opens_at,
            UNIQUE(schedule_id, travel_class, quota_type))
pool_berths(pool_id, berth_id, PRIMARY KEY(pool_id, berth_id))

bookings(id, pnr UNIQUE, schedule_id, travel_class, quota_type,
         from_seq, to_seq, status, booking_class, passenger_count,
         fare_paise, user_id, created_at, confirmed_at, cancelled_at,
         idempotency_key)
  -- status: HELD | PAYMENT_PENDING | CONFIRMED | CANCELLED | EXPIRED
  --       | FAILED | FAILED_REFUNDED
  -- booking_class: CNF | RAC | WL

passengers(id, booking_id, name, age, gender, berth_id NULL, coach_code NULL)
```

### 10.2 The allocation table and the database-enforced invariant

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE seat_allocations (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT NOT NULL REFERENCES schedules(id),
    berth_id     BIGINT NOT NULL REFERENCES berths(id),
    booking_id   BIGINT NOT NULL REFERENCES bookings(id),
    seg_range    INT4RANGE NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT no_overlapping_allocations EXCLUDE USING gist (
        schedule_id WITH =,
        berth_id    WITH =,
        seg_range   WITH &&
    )
);
```

**This constraint is the most important line of SQL in the project.** It makes overbooking *structurally impossible* at the storage layer, independent of whether the allocator has a bug. If Strategy A's Lua script or Strategy B's in-memory owner ever produces an overlapping allocation, the confirmation transaction fails loudly instead of silently double-selling a berth.

Requirements:
- **INV-1** is enforced here *and* checked independently by the invariant checker (§14), so a checker bug cannot mask a real violation.
- **AC-1.8:** a test must deliberately attempt an overlapping insert and assert the constraint rejects it.
- Range is half-open `[from_seq, to_seq)` to match §5.2 semantics.

### 10.3 Payments, refunds, ledger

```sql
payments(id, booking_id, psp_payment_id UNIQUE, amount_paise, status,
         initiated_at, settled_at)
  -- status: INITIATED | SUCCESS | FAILED | REFUNDED
payment_events(id, psp_payment_id, event_type, payload JSONB, received_at,
               UNIQUE(psp_payment_id, event_type))   -- webhook idempotency
refunds(id, booking_id, payment_id, amount_paise, reason, status, created_at)
ledger_entries(id, booking_id, entry_type, amount_paise, created_at)
  -- entry_type: CHARGE | REFUND
```

### 10.4 Waitlist and outbox

```sql
waitlist_entries(id, schedule_id, travel_class, booking_id, position,
                 entry_type, created_at, promoted_at,
                 UNIQUE(schedule_id, travel_class, entry_type, position))
  -- entry_type: RAC | WL

outbox(id, aggregate_type, aggregate_id, event_type, payload JSONB,
       created_at, published_at NULL)
idempotency_keys(key PRIMARY KEY, user_id, request_hash, response JSONB,
                 created_at)
checkpoints(partition_key PRIMARY KEY, kafka_offset, generation_id,
            mask_snapshot BYTEA, updated_at)   -- Strategy B only
```

### 10.5 Redis key schema

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `masks:{sched}:{cls}:{pool}` | String (packed longs) | Berth masks, Strategy A | none |
| `freecount:{sched}:{cls}:{pool}` | String (packed ints) | Per-segment free counts | none |
| `holds:{sched}:{cls}:{pool}` | ZSET | holdId → expiry epoch ms | none |
| `hold:{holdId}` | Hash | berths, mask, bookingId | 150 s |
| `queue:{sched}:{cls}` | ZSET | Admission FIFO, score = issue ms | none |
| `qtoken:{token}` | Hash | status, admittedAt | 300 s |
| `search:{train}:{date}:{from}:{to}:{cls}` | String | Cached availability | 2 s |
| `rate:{userId}` | String | Per-user rate limit counter | 1 s |

### 10.6 Seed data

- **FR-48** A generator produces: 20 trains, routes of 8–25 stops, 4–8 coaches per train across 3–5 classes, schedules for 30 forward days. Roughly 300k berths total.
- **FR-49** Three trains are designated **hot** and receive disproportionate load in profile P3.
- **FR-50** Generation is deterministic given a seed, so benchmark runs are comparable across strategies.

---

## 11. API contract

Base path `/api/v1`. Errors use RFC 7807 `application/problem+json`.

| ID | Method & path | Purpose |
|---|---|---|
| API-1 | `GET /trains/search?from&to&date&class` | Train list + approximate availability |
| API-2 | `POST /queue/token` | Request admission token (queued mode) |
| API-3 | `GET /queue/{token}` (+ SSE) | Queue position, ETA, admission status |
| API-4 | `POST /bookings/hold` | Allocate berths, create hold |
| API-5 | `POST /bookings/{id}/pay` | Initiate payment |
| API-6 | `POST /payments/webhook` | PSP callback (HMAC-signed) |
| API-7 | `POST /bookings/{pnr}/cancel` | Cancel + refund + promotion |
| API-8 | `POST /admin/schedules/{id}/chart` | Trigger chart preparation |
| API-9 | `GET /bookings/{pnr}` | Booking detail |
| API-10 | `GET /admin/schedules/{id}/seatmap` | Live berth occupancy for the UI |
| API-11 | `GET /actuator/prometheus` | Metrics |

### 11.1 Representative request/response

`POST /bookings/hold`

```http
Idempotency-Key: 8f3c...   (required)
X-Queue-Token: qt_...      (required only in queued mode)

{ "scheduleId": 4412, "travelClass": "3A", "quotaType": "TATKAL",
  "fromStationCode": "NDLS", "toStationCode": "BCT",
  "passengers": [ {"name":"...", "age":34, "gender":"M"} ] }
```

```json
{ "holdId": "h_9c21...", "bookingId": 88213, "bookingClass": "CNF",
  "expiresAt": "2026-09-02T10:00:47Z", "fareePaise": 245000,
  "allocations": [ {"coach":"B3","berth":41,"berthType":"LOWER"} ] }
```

### 11.2 Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `SEAT_UNAVAILABLE` | 409 | No berth free for that segment range |
| `QUOTA_LOCKED` | 409 | Tatkal window not yet open |
| `QUOTA_EXHAUSTED` | 409 | CNF + RAC + WL all full |
| `HOLD_EXPIRED` | 410 | Hold TTL elapsed |
| `QUEUE_REQUIRED` | 429 | Partition in queued mode; obtain a token |
| `QUEUE_NOT_ADMITTED` | 425 | Token valid, not yet admitted |
| `DUPLICATE_REQUEST` | 200 | Idempotency replay; original response returned |
| `RETRY_LATER` | 503 | Partition owner replaying (Strategy B) |
| `CHART_PREPARED` | 409 | Booking closed for this schedule |
| `RATE_LIMITED` | 429 | Per-user rate limit |

**FR-51** `SEAT_UNAVAILABLE` and `QUOTA_EXHAUSTED` are **correct outcomes**, not errors. They must be excluded from the NFR-7 error budget and counted separately in metrics. Conflating them is a common and revealing mistake.

---

## 12. Simulated payment service

A separate small Spring Boot service (`psp-sim`) in the same repo.

- **FR-52** `POST /psp/payments` accepts a charge and responds immediately with `INITIATED`.
- **FR-53** Settlement is asynchronous. Latency is drawn from a configurable distribution (default: log-normal, median 800 ms, p99 6 s).
- **FR-54** Configurable outcome mix: success / failure / **timeout-then-late-success** / **webhook-never-sent**. The last two exist specifically to exercise FR-23 and FR-24.
- **FR-55** Webhooks are HMAC-SHA256 signed and **deliberately delivered twice** for 5% of payments.
- **FR-56** `POST /psp/admin/chaos` reconfigures the mix at runtime, so a chaos scenario can degrade payments mid-run.
- **FR-57** `POST /psp/refunds` for FR-43.

---

## 13. Failure handling

### 13.1 Failure matrix

| Failure | Detection | Response | Test |
|---|---|---|---|
| Hold expires before payment | Reaper / lazy reap | Release berths, booking → `EXPIRED` | T-C1 |
| Payment succeeds after expiry | Confirm sees no hold | Auto-refund → `FAILED_REFUNDED` | T-C4 |
| Webhook delivered twice | `payment_events` unique key | Second is a no-op | T-C5 |
| Webhook never delivered | Reconciliation poll (FR-23) | Poll PSP, settle | T-C6 |
| Redis lost | Connection failure | Rebuild masks from Postgres; in-flight holds lost | C2 |
| Postgres paused | Timeout | Reject writes with 503; reads served from cache | C3 |
| Partition owner crashes | Consumer rebalance | New owner replays WAL, ≤10 s | C1 |
| Split brain on rebalance | Generation ID check | Stale-generation events rejected | T-C7 |
| Kafka broker restart | Producer retry | Buffered commands retried; no double-apply | C4 |
| Concurrent cancel + promote | DB row lock on booking | Serialised; one wins | T-C8 |

### 13.2 Hold reaper

Runs every 5 s per replica. Uses a Redis lock so only one replica reaps a given partition. Reaping is also lazy inside the allocation script (§9.2), so the reaper is an optimisation for idle partitions, not a correctness dependency. **This distinction must be stated in code comments** — it is the kind of design detail a Reviewer agent should verify.

### 13.3 Outbox pattern

Domain events (`BookingConfirmed`, `BookingCancelled`, `BookingPromoted`, `ChartPrepared`) are written to `outbox` inside the same transaction as the state change, then published to Kafka by a poller. This avoids the dual-write problem between Postgres and the log.

### 13.4 Mask rebuild

`POST /admin/schedules/{id}/rebuild-masks` reconstructs Redis masks (Strategy A) or in-memory masks (Strategy B) from `seat_allocations`. Required for C2 recovery and useful as a standalone consistency repair. Rebuild must be safe to run against a live partition (it acquires the partition and blocks commands with `RETRY_LATER` during rebuild).

---

## 14. Invariants

Every invariant has an executable check in `ops/invariant-checker`, run after every load and chaos scenario. **NFR-9: any violation fails the build.**

| ID | Invariant | Check |
|---|---|---|
| INV-1 | No berth has two allocations with overlapping segment ranges | SQL self-join on `seat_allocations` with `&&`; must return 0 rows |
| INV-2 | Every `CONFIRMED` booking has exactly one `SUCCESS` payment | Join count |
| INV-3 | No `SUCCESS` payment is orphaned — its booking is `CONFIRMED`, `CANCELLED` or `FAILED_REFUNDED` | Anti-join |
| INV-4 | For every `(schedule, class, segment)`: `CNF allocations ≤ pool capacity` | Aggregate per segment |
| INV-5 | No hold older than `TTL + 30 s` exists in Redis | ZSET scan |
| INV-6 | PNRs are unique and check digits valid | Unique index + Luhn recompute |
| INV-7 | For every booking: `sum(CHARGE) − sum(REFUND) == expected retained fare` | Ledger reconciliation |
| INV-8 | Redis masks match Postgres `seat_allocations` exactly (post-run, quiesced) | Rebuild and diff |
| INV-9 | Waitlist positions are contiguous with no gaps or duplicates per `(schedule, class, type)` | Window function |
| INV-10 | No booking in a terminal state has an active hold | Join |

INV-8 is the strongest check and the one that catches the subtle bugs. It runs only after the system quiesces, since during load a transient divergence is expected and legitimate.

---

## 15. Observability

### 15.1 Metrics

Business: `bookings_attempted_total{class,quota,result}`, `bookings_confirmed_total`, `seats_available{schedule,class}`, `waitlist_depth`, `promotions_total`.

Contention: `allocation_conflicts_total` — the single most important metric in this system, since it is the direct measure of contention each strategy faces. Also `allocation_attempts_per_success` (histogram), `hold_expiry_total{reason}`.

Latency: `allocation_duration_seconds` (core operation only), `hold_request_duration_seconds` (end to end), `payment_settle_duration_seconds`.

Strategy B: `partition_owner_count`, `partition_replay_duration_seconds`, `command_reply_latency_seconds`, `consumer_lag`, `stale_generation_events_total`.

Admission: `queue_depth{schedule,class}`, `admission_rate`, `queue_wait_seconds`, `tokens_expired_total`.

### 15.2 Dashboards

Three Grafana dashboards, provisioned as code:

1. **Tatkal Rush** — the demo dashboard. Request rate, queue depth, seats remaining, conflict rate, latency percentiles, all on a 30-second window. This is what you screenshot for the README.
2. **Strategy Comparison** — same panels, two series (A vs B), fed from separate runs.
3. **System Health** — JVM, connection pools, Redis ops/s, consumer lag, Postgres locks.

**AC-1.9:** dashboards are checked into the repo as JSON and auto-provisioned. A dashboard that has to be rebuilt by hand does not exist.

### 15.3 Logging

Structured JSON. Every request carries a correlation ID propagated via `ScopedValue`, and into Kafka command headers so a booking can be traced across the request/reply hop in Strategy B. Log level `INFO` during load runs — verbose logging changes the benchmark and must not be enabled.

---

## 16. Security

Intentionally minimal (NG-2), but not absent, because a system with no auth at all invites the question.

- **FR-58** Stub JWT issuer: `POST /auth/token` returns a signed JWT for a synthetic user ID. No password, documented as a stub.
- **FR-59** All booking endpoints require a valid JWT; `userId` is extracted from it and never accepted from the request body.
- **FR-60** Per-user rate limit: 10 requests/second, sliding window in Redis. Returns `RATE_LIMITED`.
- **FR-61** PSP webhooks verify HMAC-SHA256 with constant-time comparison; unsigned or mis-signed webhooks are rejected and counted.
- **FR-62** No PII beyond synthetic passenger names. No card data ever touches the system — the PSP simulator handles the notional payment instrument.

---

## 17. Frontend — React dashboard

Purpose: make the system's behaviour *visible* during a spike. This is the demo asset and the source of README screenshots and a recording. It is not a consumer product.

### 17.1 Views

**V-1 Booking flow.** Search → select train/class → passenger form → hold (with a live countdown on the hold TTL) → payment (mock button) → PNR. The hold countdown is the important detail; it makes the two-phase nature of the system legible at a glance.

**V-2 Live seat map.** Grid of berths for a `(schedule, class)`, each cell showing its segment mask as a small horizontal bar of `N-1` cells — filled where occupied. Polls `API-10` every 500 ms. During a spike this visibly fills in. **This is the single best screenshot the project can produce** and should be built well; it renders the core abstraction of §5.2 directly on screen.

**V-3 Queue position.** SSE-driven. Position, people ahead, estimated wait, admission countdown.

**V-4 Ops panel.** Embedded Grafana panels plus buttons to trigger chaos scenarios and chart preparation, so a demo can be driven end to end from one screen.

### 17.2 Constraints

- **FR-63** React 18 + Vite + TypeScript. State via TanStack Query; no Redux.
- **FR-64** No component library required — the seat map is custom SVG or CSS grid regardless.
- **FR-65** The UI must degrade rather than fail when the backend returns `QUEUE_REQUIRED` or `RETRY_LATER`; those states have designed screens, not error toasts.
- **FR-66** Visual design is delegated to the implementing agent. Constraint: dark background, high contrast, dense information — it is a control panel, not a marketing page.

---

## 18. Testing strategy

| Layer | Scope | Tooling |
|---|---|---|
| Unit | Segment mask algebra, allocation, fare/refund tiers, state machine, PNR check digit | JUnit 5 |
| Property-based | Mask invariants under random operation sequences | jqwik |
| Contract | Both `SeatAllocator` implementations against one suite | JUnit + Testcontainers |
| Integration | Full booking path per adapter | Testcontainers (PG, Redis, Kafka) |
| Concurrency | N threads racing for the last berth | `CountDownLatch` harness |
| Failure | The T-C* scenarios of §13.1 | Testcontainers + Toxiproxy |
| Load | §19 profiles | k6 |
| Chaos | C1–C5 overlaid on load | Toxiproxy + `docker kill` |
| Invariant | §14 after every run | `ops/invariant-checker` |

### 18.1 Mandatory tests

- **T-1** *Last berth race.* 500 concurrent threads request the final available berth for identical ranges. Exactly one succeeds; 499 receive `SEAT_UNAVAILABLE`. Run against both strategies. This is the test that most directly proves the central claim, and should be the first test written.
- **T-2** *Interleaved segment race.* Concurrent requests for `A→C` and `B→D` on a route with one berth. Exactly one succeeds — they overlap on `B→C`.
- **T-3** *Complementary segments.* Concurrent `A→B` and `B→C` on one berth. **Both** succeed. This is the test that fails if someone "simplifies" the design into a seat counter.
- **T-4** *Property.* For any random sequence of allocate/release operations, `INV-1` holds and released capacity always returns exactly to baseline.
- **T-5** *Idempotency.* The same `Idempotency-Key` sent 100 times concurrently produces exactly one allocation.
- **T-6** *Chart promotion.* Given `n` CNF, `m` RAC, `k` WL, cancelling `j` CNF bookings promotes exactly `j` RAC → CNF and `j` WL → RAC, positions stay contiguous (`INV-9`).

Coverage target: **≥85% on `domain/`**, no target elsewhere. Chasing coverage in adapters produces test theatre.

---

## 19. Load and chaos plan

### 19.1 Profiles

| ID | Profile | Shape | Purpose |
|---|---|---|---|
| P1 | Tatkal spike | 0 → 5,000 VU arrival rate over 10 s, hold 30 s, ramp down | NFR-2, the headline scenario |
| P2 | Sustained mixed | 2,000 rps, 90% search / 10% book, 10 min | NFR-1, NFR-3, NFR-5 |
| P3 | Hot partition | 100% of P1 load onto **one** `(train, date, class)` | The strategy discriminator |
| P4 | Soak | 500 rps, 30 min | Leak and drift detection; INV-5 |
| P5 | Cancellation storm | 1,000 rps cancels against a fully booked train | Promotion under load (FR-41) |

### 19.2 Chaos overlays

| ID | Injection | Applied during | Expectation |
|---|---|---|---|
| C1 | `docker kill` one app replica at spike peak | P1 | Strategy B: ≤10 s recovery, zero lost confirmed bookings. Strategy A: near-zero impact |
| C2 | `redis-cli FLUSHALL` mid-run | P2 | In-flight holds lost; confirmed bookings intact; masks rebuilt; INV-8 passes after rebuild |
| C3 | Toxiproxy: 5 s Postgres freeze | P2 | Writes 503, reads served, no data corruption, recovery on release |
| C4 | Kafka broker restart | P1 | Strategy B buffers and retries; no double-apply (INV-1) |
| C5 | PSP → 50% timeouts, 20% late-success | P2 | Holds expire correctly; late successes auto-refund (FR-24); INV-3 passes |

### 19.3 Reporting

`loadtest/report-generator` emits a Markdown report per run containing: profile, strategy, git SHA, hardware, container resource limits, throughput, latency table, error breakdown split into *legitimate* vs *failure* (FR-51), invariant results, and Grafana screenshots. Reports are committed under `docs/benchmarks/`.

### 19.4 Honesty requirements

Non-negotiable, and stated in the README:

- **NFR-12** Every reported number carries: hardware spec, container CPU/memory limits, whether the load generator was co-located, and the run duration.
- **NFR-13** The report states plainly that k6 and the system under test share a laptop, and that headroom on dedicated infrastructure would be higher **without estimating how much higher**.
- **NFR-14** Failed runs and violated invariants stay in `docs/benchmarks/`. The history of what broke and how it was fixed is the most interesting document in the repository, and deleting it is the single fastest way to make the project look fabricated.

---

## 20. Delivery phases

Each phase is independently shippable. If time runs out, you stop at a phase boundary with something complete.

### Phase 0 — Foundation

Repo skeleton, Gradle multi-module, Docker Compose stack, Flyway schema, seed generator, health endpoints, ArchUnit module rules, CI running unit tests.

- **AC-0.1** `docker compose up` brings the full stack healthy in ≤120 s (NFR-10), Kafka included, with a healthcheck that waits for broker readiness rather than a fixed sleep.
- **AC-0.2** Seed generator produces the §10.6 dataset deterministically in ≤60 s.
- **AC-0.3** All §10 tables created including the `EXCLUDE` constraint.
- **AC-0.4** ArchUnit fails the build on a `domain → adapters` dependency.
- **AC-0.5** `docs/design-decisions.md` exists and carries entries for every Phase 0 choice — Kafka partition count, connection pool sizing, seed data shape, module boundaries (DOC-1, DOC-3).

### Phase 1 — Core booking path (MVP, shippable)

Segment-mask domain, Strategy A, hold/pay/confirm/cancel, PSP simulator, PNR, invariant checker, k6 P1 + P2, Grafana dashboards 1 and 3.

- **AC-1.1** T-1, T-2, T-3, T-4, T-5 all pass.
- **AC-1.2** P1 completes with zero INV violations.
- **AC-1.3** P2 meets NFR-1, NFR-3, NFR-5.
- **AC-1.4** All ten invariants have executable checks; all pass.
- **AC-1.5** C5 (PSP chaos) passes: no orphaned payments, no orphaned holds.
- **AC-1.6** Allocator contract test suite exists and Strategy A passes it.
- **AC-1.8** Overlapping-insert test proves the DB constraint rejects it.
- **AC-1.9** Dashboards provisioned as code.
- **AC-1.10** Benchmark report for P1 and P2 committed.
- **AC-1.11** Every Phase 1 decision meeting the DOC-2 bar has a log entry, each with at least two rejected alternatives (DOC-4) and a falsifiable revisit condition (DOC-5). Reviewer agent verifies log-to-code consistency (DOC-6).

*At this point the project is resume-complete. Everything after this makes it stronger.*

### Phase 2 — Second strategy and the comparison

Strategy B (partitioned single-writer), WAL, checkpointing, replay, generation fencing, chaos suite C1–C4, P3 hot-partition profile, comparison report, Grafana dashboard 2.

- **AC-2.1** Strategy B passes the identical contract suite (AC-1.6) with no test modifications.
- **AC-2.2** Owner crash at spike peak recovers in ≤10 s (NFR-8) with zero lost confirmed bookings.
- **AC-2.3** P3 run for both strategies; results differ measurably and the difference is explained mechanistically, not hand-waved.
- **AC-2.4** Split-brain test T-C7 proves stale-generation events are rejected.
- **AC-2.5** Comparison report published with a "when to choose which" conclusion.
- **AC-2.6** Zero invariant violations across every chaos scenario, both strategies.
- **AC-2.7** Design decision log covers the Strategy B mechanics specifically: checkpoint interval, replay buffering, generation fencing, reply-topic partitioning, and the LRU size for command deduplication. The §9.4 comparison conclusion is promoted to an ADR (DOC-7).

### Phase 3 — Domain depth and demo surface

Tatkal window enforcement, admission control queue, RAC/WL, promotion, chart preparation, P5, React dashboard.

- **AC-3.1** Tatkal unlock is clock-driven and testable via injected `Clock` (FR-31).
- **AC-3.2** Admission controller keeps admitted-users-to-remaining-seats within 2× under P1.
- **AC-3.3** T-6 passes; INV-9 holds under P5.
- **AC-3.4** Chart preparation promotes to exhaustion and refunds remaining WL.
- **AC-3.5** Live seat map renders a P1 spike in real time without dropping frames.
- **AC-3.6** A recorded demo shows: queue → admission → booking → seat map filling → chaos injection → recovery.
- **AC-3.7** Design decision log covers the admission rate formula, RAC/WL caps, promotion ordering, and chart-time semantics. Any decision reversed during Phases 1–3 has a superseding entry with evidence (DOC-8).

---

## 21. Repository conventions

- Conventional commits, with the requirement ID in the body (`Implements: FR-16, INV-1`).
- ADRs in `docs/adr/` for: modular monolith over microservices, the two allocator strategies, single-broker Kafka in KRaft mode and its accepted SPOF, the `EXCLUDE` constraint, approximate search.
- `docs/benchmarks/` retains every run including failures (NFR-14).
- `docs/design-decisions.md` is maintained continuously by every agent (§21.1).
- README leads with the problem (§2), then the architecture diagram, then the benchmark table, then the demo GIF. Not with a technology list.

### 21.1 Design decision log — mandatory

- **DOC-1** A single running log lives at `docs/design-decisions.md`. Every agent in the pipeline appends to it. It is never rewritten, reordered, or pruned — entries are superseded, not deleted.

- **DOC-2** An entry is required whenever an agent makes a choice that a competent reviewer could reasonably have made differently. Concretely, that includes: choosing a data structure or algorithm where an obvious alternative exists; setting any timeout, TTL, batch size, pool size or retry policy; adding an index; choosing between optimistic and pessimistic locking; deciding what a failure path does (retry, fail fast, degrade, swallow); adding a dependency; deviating in any way from this SDD. It excludes mechanical work — naming, formatting, boilerplate, and anything this document already dictates.

- **DOC-3** Entry format:

```markdown
### DD-014 — Expired holds are reaped inside the allocation script
Date: 2026-09-05 · Agent: Coder-2 · Phase: 1 · Requirements: FR-18, INV-5
Supersedes: —

**Decision.** The Lua allocation script reaps expired holds for the partition
before scanning for a free berth, rather than relying solely on the background
reaper.

**Alternatives considered.**
1. Background reaper only, every 5s. Rejected: a stalled or crash-looping
   reaper silently loses inventory, and the failure is invisible until someone
   notices a train is "full" with empty berths.
2. Redis keyspace notifications on TTL expiry. Rejected: delivery is
   best-effort and not guaranteed under memory pressure — exactly when we
   would need it.

**Consequences.** Allocation latency now includes a ZRANGEBYSCORE plus up to N
mask clears, raising p99 on cold partitions. Measured at +0.4ms p99 under P2.
Correctness no longer depends on any background process.

**What would change this.** If the reap cost shows up as a material share of
allocation latency under P3, move to a bounded reap (max 50 holds per call)
with the remainder left to the background reaper.
```

- **DOC-4** The **Alternatives considered** section may not be empty. "No alternatives were considered" is itself a reviewable failure, and the Reviewer agent must reject the entry rather than accept it. Two alternatives with honest reasons for rejection are the minimum bar; the reasons must be specific to this system, not generic ("it's slower" is not a reason, "it adds a round trip on the hot path measured at X" is).

- **DOC-5** The **What would change this** section is mandatory and must name an observable condition — a metric, a benchmark result, a scale threshold. A decision that cannot be falsified was not a decision.

- **DOC-6** The Reviewer agent rejects any change that alters an approach without a corresponding entry, and rejects entries where the stated reasoning does not match what the code actually does. Drift between the log and the code is worse than no log.

- **DOC-7** When an entry proves architecturally load-bearing — it constrains later work, or it is the answer to "why is this system shaped like this" — promote it to a numbered ADR in `docs/adr/` and link back to the originating `DD-*`. The log is the raw stream; ADRs are the curated subset.

- **DOC-8** When a decision is reversed, add a new entry that names the superseded `DD-*` ID and states what evidence caused the reversal. Reversals with evidence are the most valuable entries in the file, and they are what make the project's engineering visible rather than merely asserted.

- **DOC-9** The Test agent appends entries for testing choices too — what was left untested and why, what a flaky test was traded against, why a coverage gap is acceptable. Untested surface that was chosen deliberately reads very differently from untested surface that was overlooked.

---

## 22. Resume framing

For the two-line project entry, once Phase 2 is complete:

> **TatkalRush** — Railway reservation engine handling segment-wise seat inventory under Tatkal-style demand spikes. Implemented two interchangeable concurrency strategies — Redis-Lua atomic allocation and a Kafka-partitioned single-writer with WAL recovery — and benchmarked them head-to-head at 5k req/s peak on a single machine. Ten machine-checked invariants (zero overbooking, zero double-charge) verified after every load and chaos run; Postgres GiST exclusion constraints make overlapping allocations structurally impossible.

What makes this defensible in an interview is not the throughput number. It is that you can answer "why did you pick that design?" with a measurement instead of an opinion, and "what breaks?" with a chaos report instead of a guess.

---

## 23. Open questions register

Empty at v1.0 — all design decisions were resolved before drafting. Agents that encounter genuine ambiguity append here with the requirement ID, the ambiguity, and the options considered, and escalate rather than guess.

| ID | Section | Question | Raised by | Status |
|---|---|---|---|---|
| — | — | — | — | — |

---

## Appendix A — Allocation algorithm

```
ALLOCATE(scheduleId, class, pool, fromSeq, toSeq, passengerCount, holdId, ttl):

  requestMask ← ((1 << toSeq) - 1) XOR ((1 << fromSeq) - 1)
  chosen ← []

  REAP_EXPIRED(pool, now)                    // lazy, see §9.2

  for berth in pool.berths ordered by ordinal:
      if (berth.mask AND requestMask) == 0:
          chosen.append(berth)
          if chosen.size == passengerCount: break

  if chosen.size < passengerCount:
      return UNAVAILABLE                     // caller falls through to RAC/WL

  for berth in chosen:
      berth.mask ← berth.mask OR requestMask

  for seg in segments(requestMask):
      freeCount[seg] ← freeCount[seg] - passengerCount

  CREATE_HOLD(holdId, chosen, requestMask, now + ttl)
  return OK(chosen)
```

Strategy A executes this inside a Redis Lua script. Strategy B executes it on the partition owner's single consumer thread. The logic is byte-for-byte the same because both call into `domain/inventory` — which is what makes §9.4 a controlled experiment rather than a comparison of two different programs.

## Appendix B — Segment mask worked example

Route: `NDLS(0) → KOTA(1) → RTM(2) → ST(3) → BCT(4)`, 4 segments.

| Booking | Range | Mask | Berth mask after | Notes |
|---|---|---|---|---|
| — | — | — | `0000` | Empty berth |
| B1 NDLS→RTM | `[0,2)` | `0011` | `0011` | Allocated |
| B2 ST→BCT | `[3,4)` | `1000` | `1011` | No overlap, allocated |
| B3 KOTA→ST | `[1,3)` | `0110` | `1011` | `0110 & 1011 = 0010` → rejected |
| B4 RTM→ST | `[2,3)` | `0100` | `1111` | `0100 & 1011 = 0` → allocated |
| B1 cancelled | `[0,2)` | `0011` | `1100` | `mask AND NOT 0011` |

One berth, three concurrent passengers, no overbooking. This table should appear in the README — it explains the entire project in fifteen seconds.
