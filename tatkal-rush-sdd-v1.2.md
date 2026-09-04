# TatkalRush — Software Design Document

**Version:** 1.2
**Status:** Ready for agent decomposition
**Amendment 1.2.1 (2026-09-04):** Performance targets recalibrated to the machine that will actually build and run this — 7.9 GB RAM, 8 CPUs, not the 16 GB laptop v1.2 assumed. NFR-11 lowered to a measured budget with per-container limits; NFR-1 and NFR-2 withdrawn as fixed targets and deferred to a Phase 0 calibration gate (AC-0.7) rather than guessed downward; Spring Boot pinned to an exact patch. Rationale in `docs/design-decisions.md` as DD-019.
**Changes since 1.1:** Design review of 2026-09-04. Seven correctness holes closed (§9.3 fencing, §10.2 constraint handling, §9.3 command idempotency, FR-19 replay, FR-41 waitlist positions, free-count integrity, §9.3 checkpointing); three gaps filled (fare computation FR-67/FR-68, admission-control parameters, synthetic users FR-69); build tool changed to Maven; Java/Spring versions pinned exactly; two new invariants (INV-11, INV-12); one new test (T-7); NFR-8 split into warm and cold cases; Phase 2 declared the finish line, Tatkal window moved to Phase 1, Phase 3 split into 3a/3b; Phase 1 acceptance criteria renumbered contiguously (v1.1 had no AC-1.7). Full rationale for every change is recorded in `docs/design-decisions.md` as DD-001…DD-018; the change register is `sdd-v1.2-changelist.md`.
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

**The log already exists.** `docs/design-decisions.md` carries DD-001 through DD-018 from the v1.1 design review. Read it before making a decision that touches the same ground; several entries name the specific measurement that would reverse them, and supplying that measurement is part of the work.

---

## 1. Purpose and framing

TatkalRush is a railway seat reservation system modelled on Indian Railways' Tatkal scheme. It exists to demonstrate the design and implementation of a system that stays **correct** under extreme, bursty write contention — the condition where most booking systems quietly fail.

The engineering claim this project must be able to defend in an interview:

> Under a 30-second traffic spike targeting a single train's inventory, the system allocated seats with zero overbooking, zero double-charges and zero orphaned holds, using two different concurrency strategies whose trade-offs were measured rather than assumed.

Everything in this document serves that claim. Features that do not serve it are deliberately excluded (§4).

Note that the claim requires **two** strategies. Phase 1 ships one; the claim is not supported until Phase 2 completes. §20 is explicit about this.

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
- **G-2** Two interchangeable concurrency strategies behind one interface, benchmarked head to head under identical load. The allocation algorithm is *specified* once and *implemented* twice (§9.1); equivalence is a tested property, not a shared-code property.
- **G-3** An end-to-end booking lifecycle: search → hold → pay → confirm → PNR → cancel → refund.
- **G-4** Machine-verified invariants after every load and chaos run.
- **G-5** A reproducible load and chaos harness that runs on a single developer laptop, with its targets calibrated to that laptop rather than assumed (§7, AC-0.7).
- **G-6** Observability sufficient to explain *why* a strategy performs the way it does, not just that it does.

## 4. Non-goals

Explicitly out of scope. Agents must not implement these.

- **NG-1** Real payment gateway integration. A simulated PSP with controllable latency and failure rates is in scope; Razorpay/Stripe is not.
- **NG-2** User accounts, profiles, password reset, KYC, ID verification. Auth is a stub JWT issuer (§16).
- **NG-3** Concession categories, senior citizen quotas, ladies quota, defence quota. Only GENERAL and TATKAL pools exist.
- **NG-4** The full IRCTC refund slab matrix. A simplified three-tier rule is specified in **FR-44**.
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
| RAC | Reservation Against Cancellation — a paid booking with no berth, ahead of WL in the promotion queue |
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

**FR-3a — Lua representation constraint (DD-002).** Strategy A executes mask operations inside Redis, which embeds **Lua 5.1**. Lua 5.1 has no integer type: every number is a double, exact only to 2^53. A mask above 2^53 is silently rounded, and a rounded mask is a wrong availability answer with no error raised.

Therefore the Lua implementation **must** use the `bit` library (`bit.band`, `bit.bor`, `bit.bnot`) with the 64-bit mask split across two 32-bit halves. A property test must explicitly exercise **segment 63** to prove the split holds at the boundary. Naive arithmetic on masks in Lua is a defect regardless of whether current seed data can reach the affected range.

### 5.3 Berth allocation preferences

- **FR-5** Within a class, allocate from the berth with the **lowest ordinal that satisfies FR-1**. Deterministic allocation makes tests reproducible and makes the two strategies in §9 directly comparable.
- **FR-6** A booking of `k` passengers must allocate `k` berths in a single atomic operation. Partial allocation is forbidden; either all `k` are held or none are.
- **FR-7** Group bookings do not require adjacent berths. (Simplification; recorded so the Reviewer agent does not flag it.)

### 5.4 Quota pools

- **FR-8** Each `(schedule, class)` has two pools: `GENERAL` and `TATKAL`. Pool membership is assigned at schedule creation by partitioning the berth set — a berth belongs to exactly one pool.
- **FR-9** `TATKAL` pool size is `ceil(0.10 × class_capacity)`, minimum 1 berth.
- **FR-10** The `TATKAL` pool is locked until the Tatkal window opens (**FR-28, FR-29**). Requests against a locked pool return `QUOTA_LOCKED`.
- **FR-11** Unsold `TATKAL` berths are **not** released into `GENERAL` before chart preparation. At chart preparation they *are* merged into `GENERAL` — see FR-42(a.5).

---

## 6. Functional requirements — booking lifecycle

### 6.1 Search and availability

- **FR-12** `API-1` returns trains serving a `(from, to, date)` query, with per-class availability counts.
- **FR-13** Availability count for a segment range is the number of berths in the pool whose mask does not conflict. Computing this exactly on every search is too expensive at spike load; the system maintains a **per-segment free-count array** of `N-1` integers per `(schedule, class, pool)` in Redis, and reports `min(free_count[i]) for i in requestMask` as an **upper bound** estimate.

  The array is initialised to `pool.berth_count` for every segment at schedule creation, in the same script that seeds the masks. It is mutated by seven paths — allocate, lazy reap, background reaper, explicit release, cancellation, promotion, chart preparation — and is therefore checked by **INV-12** (DD-012).
- **FR-14** Search results are explicitly labelled as approximate. Exactness is only guaranteed at hold time. This is the correct trade-off and mirrors real systems; the Reviewer agent should treat any attempt to make search strongly consistent as a defect. "Approximate" licenses a *bounded* imprecision in the upper-bound estimate; it does not license unbounded drift from a bookkeeping bug, which is what INV-12 exists to catch.
- **FR-15** Search responses are cached in Redis for 2 seconds, keyed by `(train, date, from, to, class, **pool**)`. The pool is part of the key because GENERAL and TATKAL availability differ by definition (FR-10) — omitting it serves one pool's answer for the other's query. During a Tatkal spike this cache collapses read amplification by orders of magnitude.

### 6.2 Hold

- **FR-16** `API-4` atomically allocates `k` berths and creates a `HOLD` record with a TTL.
- **FR-17** Hold TTL is **120 seconds** (configurable via `tatkal.hold.ttl`). Real IRCTC allows longer; 120s keeps load-test cycles short.
- **FR-18** Holds must be released on expiry, restoring the berth masks. Expiry is driven by a reaper (§13.2), not by TTL alone — a Redis TTL removes the hold record but cannot itself clear the mask bits.
- **FR-19** Every hold request carries an `Idempotency-Key` header. Replay of the same key within 10 minutes returns the **current representation** of the original booking, not a stored copy of the original response (DD-010).

  - `idempotency_keys` stores `key → bookingId`. It does **not** store a response body.
  - On replay the system re-renders from current booking state. A replay after the hold expired returns the booking as `EXPIRED`; a replay after confirmation returns `CONFIRMED` with the PNR. The answer is always true at the moment it is given.
  - Same key with a **different** `request_hash` returns `409 IDEMPOTENCY_KEY_REUSED`.
  - The key row is inserted **first**, under its primary key, inside a transaction, *before* allocating. Concurrent duplicates lose on the unique constraint and resolve to the winner's `bookingId` rather than allocating. Check-then-act is a defect; see T-5.

  The 10-minute window is safe precisely because the response is never frozen. A frozen response outlives the 120-second hold TTL and becomes an affirmative lie, which manufactures FR-24 races that never happened and contaminates C5's measurement.
- **FR-20** Holds are rejected when the caller has more than 3 active holds.

### 6.3 Payment

- **FR-21** `API-5` initiates payment against a held booking. The system calls the simulated PSP (§12) and transitions the booking to `PAYMENT_PENDING`.
- **FR-22** `API-6` receives PSP webhooks. Webhooks are HMAC-signed, may arrive **more than once**, **out of order**, or **not at all**. Handling must be idempotent on `(payment_id, event_type)`.
- **FR-23** A reconciliation job polls the PSP every 30 seconds for payments in `PAYMENT_PENDING` older than 60 seconds, covering the never-arrives case.
- **FR-24** If payment succeeds after the hold expired and the berth was reallocated, the payment is **auto-refunded** and the booking moves to `FAILED_REFUNDED` with `refunds.reason = 'HOLD_EXPIRED'`. This race must be tested explicitly (T-C4).

  Hold expiry is evaluated from `bookings.hold_expires_at` in Postgres, **not** from Redis. Redis may be absent (chaos scenario C2 runs concurrently with live payments during P2), and a payment-side decision must not depend on a cache.

### 6.4 Confirmation and PNR

- **FR-25** On payment success, confirmation proceeds in this order, and the order is binding (DD-008):

  1. **Validate the hold is live** (`hold_expires_at > now`). If not, FR-24 applies — auto-refund with reason `HOLD_EXPIRED`.
  2. **Insert `seat_allocations`** rows. If the `no_overlapping_allocations` constraint rejects the insert *while the hold was live*, that is an allocator bug, not an expiry race: auto-refund with reason `ALLOCATION_CONFLICT`, increment `allocation_constraint_violations_total`, and the run fails (NFR-9, INV-11).
  3. **Transition `HELD → CONFIRMED`**, convert hold records to permanent allocations, and issue a PNR.

  Steps 1 and 2 must not be reordered. Reordering makes a benign expiry race indistinguishable from an allocator defect, and the two demand opposite responses.
- **FR-26** PNR is a 10-digit string derived from a Postgres sequence plus a Luhn check digit. Random generation with collision retry is forbidden — it degrades under exactly the load this project is about.
- **FR-27** Booking state machine:

```
     ┌─────────┐  hold expires / user releases   ┌──────────┐
     │  HELD   │───────────────────────────────▶ │ EXPIRED  │
     └────┬────┘                                 └──────────┘
          │ initiate payment
          ▼
  ┌───────────────┐   psp failure    ┌────────────┐
  │PAYMENT_PENDING│─────────────────▶│   FAILED   │
  └───────┬───────┘                  └────────────┘
          │ psp success
          │
          ├─── hold live, allocations inserted ────────▶ ┌───────────┐
          │                                              │ CONFIRMED │
          │                                              └─────┬─────┘
          │                                                    │ cancel
          ├─── hold already expired ──────────┐                ▼
          │    (reason: HOLD_EXPIRED)         │         ┌───────────┐
          │                                   │         │ CANCELLED │
          └─── allocation conflict at ────────┤         └───────────┘
               confirm, hold was live         │
               (reason: ALLOCATION_CONFLICT)  │
               — this is a bug; see INV-11    │
                                       ┌──────▼────────┐
                                       │FAILED_REFUNDED│
                                       └───────────────┘
```

State transitions are the single source of truth for the Reviewer agent. Any transition not on this diagram is a defect. In particular there is **no** `HELD → CANCELLED` edge: cancelling an unpaid hold is a *release*, which lands on `EXPIRED` (FR-43).

Both paths into `FAILED_REFUNDED` share a terminal state but are separated by `refunds.reason`. One is expected; the other fails the build.

### 6.5 Tatkal window

- **FR-28** Each `(schedule, class)` has a Tatkal open instant: **10:00:00 IST on D-1** for AC classes (`1A`,`2A`,`3A`,`CC`), **11:00:00 IST on D-1** for `SL`.
- **FR-29** Before the open instant, hold requests against the `TATKAL` pool return `QUOTA_LOCKED` with the opening time in the response.
- **FR-30** The unlock must not require a scheduled job to "flip a switch" — it is a pure function of clock time, evaluated per request. A job-based unlock introduces a window where the job has not yet run and creates an artificial thundering herd on the job itself.
- **FR-31** System time is injected via a `Clock` bean so load tests can simulate the window without waiting for 10 AM. This is mandatory; a test suite that cannot control time cannot test this system.

FR-28 through FR-31 are **Phase 1**, not Phase 3 (DD-018). They are roughly two days of work, they are the feature the project is named after, and load profile P1 is called "Tatkal spike" — it cannot run honestly in a phase where the Tatkal window does not exist.

### 6.6 Admission control (virtual waiting room)

- **FR-32** When the request rate for a `(schedule, class)` partition exceeds `admission.threshold_rps`, the system switches that partition into **queued mode**.

  Rate is measured as a **per-replica sliding window**, with the configured threshold divided by the replica count. This is an estimate rather than a true system-wide rate; the alternative — a shared Redis token bucket — adds a network round trip to the hot path of every request during the exact spike this component exists to survive, on the same Redis instance Strategy A needs for allocation (DD-015).
- **FR-32a** **Hysteresis is mandatory.** A partition enters queued mode above `threshold_rps` and leaves it only after a sustained **10 seconds** below `0.5 × threshold_rps`. Without hysteresis the partition flaps at the boundary — consecutive requests alternate between receiving holds and `QUEUE_REQUIRED` — which is incoherent for users and poisons P1's results.
- **FR-33** In queued mode, a booking attempt receives a queue token instead of a hold. `API-2` issues tokens; `API-3` returns position and estimated wait.
- **FR-34** Tokens are held in a Redis sorted set scored by issue timestamp — strict FIFO per partition. **The issued token carries the client's own score**, which FR-37 requires.
- **FR-35** An admission controller admits tokens at a rate derived from remaining inventory:

  ```
  admit_rate = max(1, remaining_berths / expected_conversion_time_s)
  ```

  recomputed every second, where:

  | Term | Definition |
  |---|---|
  | `remaining_berths` | `min(free_count[i])` over **all** route segments — the count of berths allocatable for *any* range. A single conservative scalar, O(N) over the array FR-13 already maintains. Deliberately errs low. |
  | `expected_conversion_time_s` | Config, default **30 s**. FR-36's 60-second window is the hard ceiling. |

  Admitting far more users than there are seats is the failure mode this component exists to prevent, so the conservative definition of `remaining_berths` is the correct direction to err.
- **FR-35a** The queue is bounded. When projected wait — `queue_depth / admit_rate` — exceeds `admission.max_wait_horizon_s`, new token requests are rejected with `QUEUE_FULL`. With ~100 berths remaining and an admit rate near 3/s, an unbounded queue of 150,000 represents a 14-hour wait; issuing those tokens is a lie with extra steps, and the Redis sorted set has no TTL to reclaim them.
- **FR-36** An admitted token grants a 60-second window to complete a hold. Unused windows are reclaimed.
- **FR-37** Queue position is streamed to the client via SSE (`API-3` supports both polling and SSE). Position is delivered as a **broadcast watermark**, not a per-user rank (DD-016):

  - Once per second the system publishes one number — the score of the most recently admitted token.
  - Each client computes `my_position = my_score − watermark` from the score its token carries (FR-34).

  Per-user `ZRANK` at P1 queue depth is roughly 150,000 O(log N) Redis operations per second, competing directly with the allocation script Strategy A depends on for correctness. It would surface in the benchmark as "Strategy A is slow under spike" and corrupt §9.4. The watermark is one read per second, broadcast.

  Position is therefore approximate — it does not account for abandoned or expired tokens ahead of the viewer — and the UI must present it as such.

### 6.7 RAC, waitlist, chart preparation

- **FR-38** After the `CNF` berths of a class are exhausted for the requested range, requests are offered `RAC`. RAC capacity is `2 × side_lower_berth_count` per class, capped at 10% of class capacity.

  Two clarifications. First, exhaustion is evaluated **per requested range** while the cap is **per class**, so the RAC allowance is a rationing quota rather than a physical capacity — different ranges exhaust at different times and draw on the same allowance. Second, since FR-40 removes berth assignment from RAC entirely, `2 × side_lower_berth_count` is a **policy dial calibrated to IRCTC's real ratio**, not a physical constraint. FR-48 must specify a berth-type distribution or the number is not computable from seed data.
- **FR-39** After RAC is exhausted, requests are offered `WL`, capped at 25% of class capacity. Beyond that, `QUOTA_EXHAUSTED`.
- **FR-40** RAC and WL bookings are still paid bookings with PNRs; they simply carry no berth assignment.
- **FR-41** On cancellation of a `CNF` booking, the freed berth range triggers promotion: the oldest RAC entry **whose range fits** is promoted to CNF, then the oldest WL entry is promoted to RAC. Promotion is transactional and emits a `BookingPromoted` event.

  **Waitlist order is stored as a monotonic `seq`, never as a materialised contiguous position** (DD-011). Promotion sets `promoted_at` and leaves the row in place; nothing is ever renumbered. "Oldest whose range fits" is a single indexed query:

  ```sql
  SELECT ... WHERE schedule_id = ? AND travel_class = ? AND entry_type = 'RAC'
    AND promoted_at IS NULL
    AND <range fits freed range>
  ORDER BY seq LIMIT 1
  ```

  Displayed position is derived at read time (§10.4). A cancellation writes two rows, not the full queue tail — under P5's 1,000 rps against a large class, renumbering would mean roughly 245,000 serialised row-writes per second on a unique index, which deadlocks rather than slows.
- **FR-42** Chart preparation runs at T-4h from origin departure (`API-8` triggers it manually for demos). It:
  - (a) finalises all allocations,
  - **(a.5) merges unsold `TATKAL` berths into `GENERAL`**, making them available to promotion,
  - (b) runs promotion to exhaustion,
  - (c) cancels remaining WL bookings with full refund,
  - (d) marks the schedule `CHARTED`.

  After charting, no new bookings are accepted. Step (a.5) resolves FR-11, which previously specified only the pre-charting behaviour; without it a charted train shows empty berths beside a non-empty waitlist.

### 6.8 Cancellation and refund

- **FR-43** `API-7` cancels a `CONFIRMED` booking, releases its berth range, triggers promotion (FR-41), and issues a refund.

  Cancelling a booking still in `HELD` is a **release**, not a cancellation: the berths are returned, the booking moves to `EXPIRED`, and no refund path is involved because no money moved. There is no `HELD → CANCELLED` transition (FR-27).
- **FR-44** Simplified refund rule — three tiers by time before departure:

| Window | Refund |
|---|---|
| > 48 h | 90% of fare |
| 12–48 h | 50% of fare |
| < 12 h | 0% |

- **FR-45** **Confirmed TATKAL bookings receive no refund on cancellation**, regardless of window. This is a real IRCTC rule and one of the "interesting" ones worth keeping.
- **FR-46** WL bookings cancelled by chart preparation receive a 100% refund, overriding FR-44.
- **FR-47** Every refund writes a `refunds` row and a ledger entry. `INV-7` checks the ledger balances.

### 6.9 Fare

Fare was referenced in five places in v1.1 and defined in none of them, which made INV-7 uncheckable and therefore blocked AC-1.4 (DD-014).

- **FR-67** Fare is a pure function of distance and class:

  ```
  fare_paise = ceil(distance_km × class_rate_paise_per_km) + class_base_paise
  ```

  `distance_km` is summed over `train_stops.distance_km` across the journey's segments `[from_seq, to_seq)`. Per-class rates are a checked-in constant table (`SL` cheapest through `1A` dearest). The function lives in `domain/pricing/` and performs no I/O.

  Worked example — NDLS→RTM in 3A, where segment 0 (NDLS→KOTA) is 465 km and segment 1 (KOTA→RTM) is 265 km:

  ```
  distance = 465 + 265                      = 730 km
  3A rate  = 285 paise/km   (illustrative)
  3A base  = 4,000 paise
  fare     = ceil(730 × 285) + 4000
           = 208,050 + 4,000                = 212,050 paise  (₹2,120.50)
  ```

- **FR-68** `TATKAL` bookings add a flat per-passenger surcharge by class, on top of FR-67. This is real IRCTC behaviour and gives FR-45's no-refund rule something material to bite on.

- **FR-67a** Fare is computed **once, at hold time**, and frozen onto `bookings.fare_paise`. It is never recomputed at confirm, cancel, or chart time. Recomputation would mean a single rate-table edit silently changes the expected value for every historical booking, breaking INV-7 across the whole dataset at once.

- **FR-67b** INV-7 must recompute expected retained fare **independently** from `(distance, class, quota, cancelled_at, departure_time)` — never by reading `bookings.fare_paise`. Comparing the stored value to itself is a tautology that passes while pricing is wrong.

---

## 7. Non-functional requirements

Numbers are calibrated to a single laptop running the full stack **plus** the load generator, and must be reported alongside the hardware and the co-location caveat (§19.4). An inflated number that cannot be reproduced is worse than no number.

**The build machine is 7.9 GB / 8 CPUs, not the 16 GB v1.2 assumed (DD-019).** Memory is a budget and has been re-divided below. Throughput is *not* a budget — it is an outcome of the hardware — so NFR-1 and NFR-2 are **not** halved by guesswork. They carry no number until measurement supplies one: **AC-0.7** establishes the box's hardware ceiling in Phase 0, and **AC-1.13** sets NFR-1 and NFR-2 from the real endpoints at checkpoint 1c. Both become entries in `docs/benchmarks/`. A requirement invented to look plausible is the failure mode §19.4 exists to prevent.

| ID | Requirement | Target |
|---|---|---|
| NFR-1 | Sustained mixed-workload throughput (90% read / 10% write) | **Set by AC-0.7.** v1.2's ≥ 2,000 req/s retained only as the 16 GB reference figure |
| NFR-2 | Peak spike throughput, 30 s window, single hot partition | **Set by AC-0.7**, accepted-or-queued. v1.2's ≥ 5,000 req/s retained only as the 16 GB reference figure |
| NFR-3 | Hold endpoint latency, p99, at NFR-1 load | ≤ 150 ms |
| NFR-4 | Hold endpoint latency, p99, at NFR-2 spike | ≤ 800 ms |
| NFR-5 | Search endpoint latency, p99, at NFR-1 load | ≤ 50 ms |
| NFR-6 | Seat allocation core operation, p99 (excludes I/O) | ≤ 5 ms |
| NFR-7 | Error rate excluding legitimate `SEAT_UNAVAILABLE` / `QUOTA_EXHAUSTED` | ≤ 0.1% |
| NFR-8 | **Warm recovery:** rebalance from a valid checkpoint to serving (Strategy B) | ≤ 2 s |
| NFR-15 | **Cold recovery:** replay to serving with no checkpoint, 100k events in topic | ≤ 10 s |
| NFR-9 | Invariant violations **and allocation-constraint violations**, all runs | **0, non-negotiable** |
| NFR-10 | Cold start of full stack via `docker compose up` | ≤ 120 s |
| NFR-11 | Total resident memory of the running stack | ≤ 4.5 GB (§8.3 sets per-container limits) |

**On NFR-8 and NFR-15 (DD-013).** v1.1 stated a single requirement — "replay-to-serving within 10 s for a partition with 100k events" — which was unreachable by construction: checkpointing every 1,000 events means replay from a checkpoint covers at most 1,000 events, never 100k. The requirement therefore measured nothing. The two scenarios are now separated. NFR-8 is the common case that C1 exercises; NFR-15 covers cold start, and DD-007 makes NFR-8 the one under continuous pressure because every partition revocation now forces a replay.

**On NFR-1, NFR-2 and NFR-11 (DD-019).** The machine has 7.91 GB of physical RAM and 8 logical CPUs. Windows, Docker Desktop and an editor hold roughly 3 GB; k6 runs co-located and needs its own ≈ 0.6 GB during a load run. That leaves ≈ 4.3 GB for the Docker stack, so NFR-11 becomes 4.5 GB and §8.3 now assigns every container an explicit limit instead of letting defaults decide.

Throughput is a different kind of number. NFR-11 is a resource you *divide*; NFR-1 and NFR-2 are results the machine *produces*, and no arithmetic on RAM predicts them — the binding constraint is 8 shared cores, with the load generator competing for them. Halving 5,000 to 2,500 would put a guess in the requirements column and make Phase 1 fail acceptance for a reason unrelated to the code. **AC-0.7 measures the hardware ceiling in Phase 0; AC-1.13 sets these two numbers from the real endpoints at checkpoint 1c, and writes them back here.** The latency requirements NFR-3–NFR-6 are unchanged: they are stated *at* NFR-1/NFR-2 load, so they recalibrate automatically with it.

Nothing about correctness moves. §14's invariants, INV-11, INV-12, NFR-9 and the whole chaos suite are independent of load magnitude — §1's engineering claim is that allocation stays correct under a spike, not that the spike is a particular size. §19.4's honesty requirements were written for exactly this situation and carry it without amendment.

**On NFR-9 (DD-008).** A tripped `no_overlapping_allocations` constraint is included because a correct allocator cannot trip it — a firing is a detector announcing that an allocator bug shipped, and it produces a real double-charge while every §14 invariant otherwise reports green.

---

## 8. Architecture

### 8.1 Architectural stance and rationale

**TatkalRush is a modular monolith deployed as N stateless replicas.**

This is a deliberate choice and the Architect agent must not override it. The rationale, which should also appear in the project README because it is an interview asset:

The contention in this system is on a *shared resource*, not on a *service boundary*. Splitting search, booking, payment and charting into separate deployables would add network hops, distributed transactions and operational surface without reducing contention on a single train's berth inventory by one iota. The genuinely hard problem — serialising conflicting writes to overlapping segment ranges — is unchanged. Distributing it first is cargo-culting.

What *does* help is partitioning inventory and giving each partition a single writer (§9.3). That is a data-plane decision, not a deployment-topology decision, and it is implemented within the monolith.

Module boundaries are enforced at compile time (§8.2) so the system *could* be split later. Being able to explain why you chose not to split it is more valuable than having split it.

### 8.2 Module boundaries

**Maven multi-module (reactor) build** (DD-005). Dependencies point inward only; violations fail the build.

```
tatkal-rush/                    ← root POM (custom parent; imports spring-boot-dependencies as BOM)
├── domain/              ← pure domain. Zero framework dependencies. No --enable-preview.
│   ├── inventory/       segment masks, berth allocation, quota pools
│   ├── booking/         booking aggregate, state machine, PNR
│   ├── waitlist/        RAC/WL entities, promotion rules
│   └── pricing/         fare (FR-67, FR-68), refund tier calculation
├── application/         ← use cases, ports, orchestration
│   ├── ports/           SeatAllocator, PaymentGateway, EventPublisher (interfaces)
│   └── usecases/        HoldSeats, InitiatePayment, ConfirmBooking, Cancel, PrepareChart
├── adapters/
│   ├── persistence/     JPA/JDBC, Flyway migrations, outbox
│   ├── allocator-redis/ Strategy A (§9.2) — includes allocate.lua
│   ├── allocator-swp/   Strategy B (§9.3)
│   ├── messaging/       Kafka producers/consumers
│   ├── payment-sim/     simulated PSP client
│   └── web/             REST controllers, SSE, problem+json mapping   ← --enable-preview lives here
├── admission/           ← virtual waiting room, rate governor
├── ops/
│   ├── invariant-checker/
│   └── seed/            synthetic data generator
├── app/                 ← composition root, bootable jar. See OQ-3.
├── archtest/            ← ArchUnit boundary rules (AC-0.4); depends on every module
├── differential/        ← T-7 only: domain/inventory vs allocate.lua
│
└── (outside the reactor)
    ├── loadtest/        k6 scripts, chaos scripts, report generator
    └── ui/              React dashboard (§17) — built in its own Dockerfile stage
```

**Build conventions (DD-005):**
- Import `spring-boot-dependencies` as a **BOM** into `<dependencyManagement>` of the root POM. Do **not** inherit `spring-boot-starter-parent` per module.
- `ui/` and `loadtest/` are **outside the reactor**. Binding the npm lifecycle to Maven phases couples unrelated builds and defeats Docker layer caching for both.
- `maven-enforcer-plugin` on `domain/` enforces dependency convergence and bans transitives.
- `mvn dependency:go-offline` runs as a Docker layer before source copy, or NFR-10 is dominated by dependency resolution.

**Enforcement is stronger under Maven than it was under Gradle.** If `domain/pom.xml` does not declare `adapters`, the compiler cannot resolve adapter classes at all — an inward-pointing dependency becomes a **compile error**, not merely an assertion that runs later. ArchUnit (`AC-0.4`) is therefore demoted from primary enforcement to catching the subtler cases, chiefly a framework annotation reaching `domain` through a transitive.

**Critical constraint:** `domain/inventory` must contain the segment-mask allocation logic in framework-free code, with **no** knowledge of Redis, Kafka or Postgres. It is the **reference specification** of the algorithm.

Strategy B calls into it directly. **Strategy A cannot** — it executes as Lua inside the Redis process, in a different language and a different process, and the atomicity that makes Strategy A correct depends on the algorithm never leaving Redis mid-execution. The algorithm is therefore **specified once and implemented twice**, and equivalence is proven by the differential test **T-7** (§18.1), not asserted by shared code. See DD-001; v1.1's claim that both strategies "call into it" was false.

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

- `KAFKA_HEAP_OPTS=-Xms640m -Xmx640m` (DD-019; v1.2 said 1 GB, which does not fit the recalibrated NFR-11)
- `num.partitions=12` (default for `booking-commands`; see below)
- `log.retention.hours=24`, `log.segment.bytes=64MB` — the WAL only needs to outlive a replay window
- `min.insync.replicas=1` with `replication.factor=1`, because there is one broker. **This is a deliberate single-point-of-failure**, accepted so the whole stack fits on one machine, and it must be called out in the ADR and the README rather than quietly assumed away. Chaos scenario C4 restarts the broker precisely to show what the system does when that SPOF blinks.
- **Transactions enabled.** Strategy B's partition owners produce transactionally with a per-partition `transactional.id` (§9.3), and reply consumers read with `isolation.level=read_committed`.

**Partition count is a real design decision, not a default.** `booking-commands` partition count is the upper bound on Strategy B's write parallelism, since one partition means one owner thread. Twelve partitions across two replicas gives six owned partitions each — enough that the hot-partition profile (P3) still concentrates load on one owner, which is the point of that test. Agents must not raise this to "a big number"; the ceiling on a laptop is CPU cores, and over-partitioning inflates rebalance time and hurts NFR-8.

**Container memory limits are part of the design, not an ops detail (DD-019).** NFR-11's 4.5 GB is enforced per container in `compose.yaml`, so a leak surfaces as one container OOM-killed with a name attached rather than as the whole machine swapping:

| Container | `mem_limit` | Notes |
|---|---|---|
| kafka | 1024m | 640m heap plus page cache and JVM overhead |
| postgres | 512m | `shared_buffers=192MB` |
| redis | 512m | `maxmemory=384mb`, `maxmemory-policy=noeviction` — holds are not evictable |
| app-1, app-2 | 512m each | `-Xmx320m`; both replicas are required, C1 kills one |
| psp-sim | 256m | |
| prometheus | 384m | `--storage.tsdb.retention.time=6h` |
| grafana | 256m | |
| nginx, toxiproxy | 32m each | |
| **Total committed** | **≈ 3.5 GB** | Headroom to NFR-11's 4.5 GB absorbs page cache and spikes |

Docker Desktop runs on WSL2, which defaults to claiming ~50% of host RAM. A committed `.wslconfig` pinning that ceiling is a Phase 0 deliverable (AC-0.7); without it the memory available to the stack silently differs between machines and every benchmark becomes unreproducible.

### 8.4 Technology stack

Versions are **pinned exactly**. A version table containing a `+` is how two agents end up on different minors (DD-003).

| Layer | Choice | Notes |
|---|---|---|
| Language | **Java 25 (LTS)**, base image pinned **by digest** | Virtual threads and scoped values are final; structured concurrency is preview — see §8.5 |
| Framework | **Spring Boot 4.0.8** | Java 25 baseline. 3.4 targets Java 17–23 and will not run on 25. Pinned to an exact patch: `4.0.x` is the same floating-version hazard as `+` (DD-019) |
| Build | **Maven** (reactor) | §8.2. `ui/` and `loadtest/` outside the reactor |
| Database | PostgreSQL 16 | `btree_gist` extension required (§10.2) |
| Cache / holds / queue | Redis 7 | Lua scripting for atomicity. **Lua 5.1** — see FR-3a |
| Log / commands | **Apache Kafka 4.1.2** | KRaft mode, single broker, 640 MB heap, transactions enabled (§8.3). Pinned to match the `kafka-clients` 4.1.2 that Spring Boot 4.0.8 manages; v1.2's "3.7+" carried the very `+` this table forbids |
| Migrations | Flyway | |
| Metrics | Micrometer → Prometheus → Grafana | |
| Tracing | OpenTelemetry → Jaeger | **Optional profile**; off by default for memory |
| Load | k6 | |
| Chaos | Toxiproxy + `docker kill` | |
| Test | **JUnit 6.0.3**, Testcontainers 2.0.5, ArchUnit 1.5.0 (core), `PropertyRunner` | All BOM-managed except ArchUnit. jqwik removed — DD-020 |
| Frontend | React 18 + Vite + TypeScript | §17 |

**Digest pinning is not optional.** Preview classfiles (§8.5) refuse to run on a different major JDK, and a floating base image silently changes the JVM under a committed benchmark. The JDK build is added to the metadata NFR-12 requires alongside hardware and container limits.

### 8.5 Concurrency model

- Spring MVC on **virtual threads** (`spring.threads.virtual.enabled=true`). Final since Java 21. This matters concretely for Strategy B, where an HTTP handler blocks awaiting a reply from a partition owner over Kafka. With platform threads, thousands of concurrent blocked handlers would exhaust the pool; with virtual threads the blocking style is affordable and the code stays readable. **This is the load-bearing Loom usage in the system.**
- **Scoped values** for request-scoped context (correlation ID, admission token) instead of `ThreadLocal`, which does not behave well with virtual threads at this scale. Final in Java 25 (JEP 506).
- **Structured concurrency** (`StructuredTaskScope`) for search fan-out across trains, so a slow train's availability lookup cannot stall the whole response and cancellation propagates cleanly. **Preview in Java 25** (JEP 505, fifth preview). Retained deliberately, and quarantined (DD-004):

  - `--enable-preview` applies to **`adapters/web` only**. Never to `domain/`, which keeps the 85%-coverage module and its property tests off preview bytecode entirely.
  - The flag must appear in **three** places for that module: `maven-compiler-plugin` `<compilerArgs>`, `maven-surefire-plugin` `<argLine>`, and the Dockerfile's runtime JVM args. Omitting the surefire entry fails as an opaque `UnsupportedClassVersionError` that reads like a JDK mismatch.
  - **API shape, binding.** The Java 25 preview rewrote this API. Use `StructuredTaskScope.open(Joiner.allSuccessfulOrThrow())`. Do **not** use the Java 21–23 form (`new StructuredTaskScope.ShutdownOnFailure()` with `fork` / `join` / `throwIfFailed`); it does not compile on 25, and every pre-25 example and tutorial shows the old shape.
  - **AC-0.6** gates this: a Phase 0 spike proves ArchUnit parses preview bytecode before anything is built on top. **Passed 2026-09-04** (DD-021).

  - **A fourth place, found by that spike and absent from v1.2.** Any module that merely *compiles against* preview bytecode also needs the flag — it fails at `testCompile`, before a single test runs, with `class file ... uses preview features of Java SE 25`. The quarantine therefore holds only while **nothing depends on `adapters/web`**. That is true today by accident of the module graph; `archtest/` now asserts it deliberately (`nothingDependsOnThePreviewEnabledModule`). ArchUnit itself needs no flag — it parses class files rather than loading them.

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

**The algorithm is specified once and implemented twice.** `domain/inventory` holds the reference implementation in Java; Strategy B calls it directly, Strategy A reimplements it in Lua. Equivalence is a **tested property** (T-7), not a structural one. This is what makes §9.4 a controlled comparison, and it is weaker on paper than v1.1's shared-code claim and stronger in practice, because it is verifiable (DD-001).

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

All mask operations use the `bit` library with 32-bit halves, per **FR-3a**.

Reaping expired holds *inside* the allocation script (step 1) is the key design decision: it makes expiry lazy and self-healing, so a stalled background reaper cannot cause seats to be permanently lost. A background reaper still runs every 5 s (§13.2) to release seats during idle periods, but correctness does not depend on it.

**Equivalence to the reference.** Because this script is a second implementation rather than a call into `domain/inventory`, **T-7** (§18.1) is a Phase 1 gate: property-generated operation sequences run against both the Java reference and the real Lua script on a Testcontainers Redis, asserting identical results *and* identical final mask state after every step. A Lua bug that returns the right answer via wrong state — decrementing a free count by 1 instead of `passengerCount`, say — passes the contract suite and silently corrupts availability for the rest of the run.

**Durability.** Redis is a *cache with authority during the hold window only*. Confirmed allocations are written to Postgres inside the confirmation transaction. On Redis loss, masks **and free counts** are rebuilt from Postgres (§13.4); in-flight holds are lost, which is acceptable and must be tested (chaos scenario C2). Hold expiry itself is durable in `bookings.hold_expires_at`, so FR-24's decision survives a Redis flush.

**Expected characteristics.** One network round trip per allocation; contention resolved inside Redis; Redis becomes the single-threaded bottleneck for the hottest partition. Predict this before measuring it, then check the prediction — that comparison is worth more in an interview than the raw numbers.

### 9.3 Strategy B — partitioned single-writer

**Model.** Eliminate contention by construction rather than resolving it.

Inventory is partitioned by key `{trainId}:{journeyDate}:{class}`. Booking commands are published to the Kafka topic `booking-commands` keyed by that partition key. Application replicas form a consumer group; **Kafka partition assignment is the ownership protocol**. The owner of a partition holds that partition's berth masks in a plain `long[]` in heap memory and applies commands **single-threaded**. No locks, no CAS, no transactions on the hot path — the allocation is an array scan and a bitwise OR.

**Request/response over the log:**

```
HTTP handler (any replica, virtual thread)
  → publish AllocateCommand{commandId, correlationId, replyPartition, ...}
       to booking-commands
  → register CompletableFuture in a pending map
  → block on future with timeout (viable because virtual threads)

Partition owner (single consumer thread)
  → apply to in-memory masks
  → BEGIN TRANSACTION
       append AllocationEvent to booking-events   ← this is the WAL
       publish AllocateReply{correlationId, ...} to booking-replies[replyPartition]
     COMMIT
Originating replica's reply consumer (isolation.level=read_committed)
  → complete the future → HTTP response
```

**Ownership fencing.** During a rebalance two replicas can briefly believe they own a partition. A stale owner that applies a command, writes an event and **replies OK** hands a client a hold on a berth it does not own; the client then pays. Rejecting the event downstream at the projection layer protects the database but not the client, and arrives after the answer has already shipped.

Fencing therefore happens at **produce time**, using Kafka's own mechanism (DD-006):

- The owner of partition `P` produces with `transactional.id = "partition-owner-P"`.
- On assignment, the new owner calls `initTransactions()`. This bumps the producer epoch at the broker and **fences the previous producer**.
- The WAL append and the reply publish are wrapped in **one transaction**; reply consumers use `isolation.level=read_committed`.
- A fenced owner's `commitTransaction()` throws `ProducerFencedException` and it **emits no reply at all**. The originating replica's future times out into `RETRY_LATER` (HTTP 503), the client retries with the same `Idempotency-Key`, and the real owner answers.

Commits are amortised **one transaction per consumed batch** — the owner is single-threaded, so batching is natural.

The consumer generation ID is still stamped on every event and still checked at the projection layer, as **defence in depth**. It is no longer the primary mitigation.

**Revocation destroys in-memory state (DD-007).** On `onPartitionsRevoked` **or** `onPartitionsLost`, the owner **discards its in-memory `long[]` entirely**. A fenced owner may have applied commands that never committed, so its heap state is ahead of the log by an unknown amount and there is no offset that describes it. The partition may not be served again without a full checkpoint-load plus replay, even if Kafka returns it to the same replica. This is mandatory; resuming from retained masks serves allocations from corrupt state that nothing detects until INV-8 runs post-run.

**Ordering and idempotency (DD-009).** Kafka guarantees per-partition ordering, so command order is total per partition.

- **`commandId` is the `Idempotency-Key`** (or a stable hash of it). One idempotency identity flows edge-to-owner, so a client retry reproduces the same `commandId` with no coordination.
- The owner keeps a bounded, **time-evicted `Map<commandId, Reply>`** — not a set of IDs. On a duplicate it **re-publishes the cached reply** to the `replyPartition` named on the retry, so the client receives its original holdId and berths. A dedup set that swallows a retry without reproducing the response converts a double-allocation bug into a hang.
- The map is **rebuilt from the WAL** during recovery replay; each `AllocationEvent` carries its `commandId` and allocated berths, so nothing extra is persisted.
- Retention covers an in-flight command plus one retry — roughly **60 seconds**, not FR-19's 10 minutes, because replay correctness lives in Postgres (FR-19). At P1 that is ~5,000 entries per hot partition rather than 150,000.
- `dedup_evictions_before_window_expiry_total` is metered. A non-zero value means the cache is undersized and the double-allocation bug is live under exactly the load meant to disprove it.
- `orphaned_replies_total` counts replies consumed by a replica with no matching future — the normal case when the originating replica has died.

**Durability and recovery.**
- `booking-events` is the write-ahead log and the source of truth for recovery. It is produced with `acks=all`, inside the ownership transaction.
- The owner checkpoints `(partition, offset, generation, mask snapshot)` to Postgres every **5 seconds or 10,000 events**, and **off the consumer thread** (DD-013). The owner copies the `long[]` (~5.6 KB for a large class), offset and generation, and hands them to a separate writer. Copy, do not share, or the snapshot tears mid-mutation. A synchronous write here would fire every 200 ms at P1 and put a Postgres transaction on the hot path — contradicting this section's central claim.
- **The checkpoint write is generation-guarded:**
  ```sql
  UPDATE checkpoints SET ... WHERE partition_key = ? AND generation_id <= :myGeneration
  ```
  and loads select the highest `generation_id`. Kafka's producer fencing does not extend to Postgres: a zombie owner can overwrite a good checkpoint with a stale offset and stale masks, after which the next owner replays from the wrong point and rebuilds wrong masks with no error anywhere. The database is the only place that can still see that write, so the guard belongs there.
- The checkpointed offset is the last **committed** transactional offset, never one ahead of what is durable in the WAL.
- On rebalance, the new owner loads the latest valid checkpoint and replays `booking-events` from that offset before serving. Commands received during replay are buffered, not rejected.
- **Requirements NFR-8 (warm, ≤2 s) and NFR-15 (cold, ≤10 s).**
- During replay the partition returns `RETRY_LATER` (HTTP 503 + `Retry-After`) rather than a wrong answer.

### 9.4 Comparison methodology

This section defines G-2 and is the primary deliverable of Phase 2.

Both strategies run identical k6 profiles (§19), on identical seed data, with an identical invariant check afterwards. Report per strategy:

- Throughput at NFR-1 and NFR-2 profiles
- Latency p50/p95/p99/p99.9 for hold
- Behaviour on the hot-partition profile (P3) — the discriminating test
- Recovery time and lost-work count under chaos scenarios C1–C5
- Memory and CPU profile
- Lines of code and cyclomatic complexity of each adapter (honest complexity accounting)

**Threats to validity that must be stated in the report:**
- The algorithm is implemented twice (§9.1). T-7 proves behavioural equivalence but the implementations differ in language and runtime, so some measured difference is attributable to Lua-versus-JVM execution rather than to the concurrency model. Do not claim otherwise.
- Strategy B's transactional commits and `read_committed` reply consumption are a real cost of its fencing (§9.3). Report `command_reply_latency_seconds` against a non-transactional baseline so the cost of correctness is visible rather than folded into the headline.

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
  -- berth_type distribution is specified by FR-48 (needed by FR-38)

users(id, external_ref UNIQUE, created_at)     -- synthetic; FR-69

schedules(id, train_id, journey_date, status, chart_prepared_at,
          UNIQUE(train_id, journey_date))
  -- status: OPEN | CHARTED | DEPARTED | CANCELLED

quota_pools(id, schedule_id, travel_class, quota_type, total_berths, opens_at,
            UNIQUE(schedule_id, travel_class, quota_type))
pool_berths(pool_id, berth_id, PRIMARY KEY(pool_id, berth_id))

bookings(id, pnr UNIQUE, schedule_id, travel_class, quota_type,
         from_seq, to_seq, status, booking_class, passenger_count,
         fare_paise, user_id, created_at, hold_expires_at, confirmed_at,
         cancelled_at, idempotency_key)
  -- status: HELD | PAYMENT_PENDING | CONFIRMED | CANCELLED | EXPIRED
  --       | FAILED | FAILED_REFUNDED
  -- booking_class: CNF | RAC | WL
  -- hold_expires_at: durable hold expiry. FR-24's decision must not depend on
  --                  Redis, which C2 flushes during P2 alongside live payments.

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

**This constraint is the most important line of SQL in the project.** It makes overbooking *structurally impossible* at the storage layer, independent of whether the allocator has a bug.

**What happens when it fires (DD-008).** If both allocators are correct, this constraint **can never trip**. A trip is therefore not an edge case to handle gracefully — it is a detector announcing that an allocator bug reached production, at a moment when the customer's money has already been captured. The handling is specified by FR-25:

- Confirmation validates the hold is live **before** attempting the insert, so a benign expiry race (FR-24, expected during C2) is separable from an allocator defect.
- A conflict against a **live** hold auto-refunds with `refunds.reason = 'ALLOCATION_CONFLICT'`, increments `allocation_constraint_violations_total`, and **fails the run** under NFR-9.

Without this, the constraint converts a data bug into a money bug while INV-1, INV-2 and every other §14 check report green.

Requirements:
- **INV-1** is enforced here *and* checked independently by the invariant checker (§14), so a checker bug cannot mask a real violation.
- **INV-11** asserts no `ALLOCATION_CONFLICT` refund exists — this is what lets the checker see the failure above.
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
  -- reason: CANCELLED | CHART_WL_REFUND | HOLD_EXPIRED | ALLOCATION_CONFLICT
  --   HOLD_EXPIRED       — benign FR-24 race, expected under C2/C5
  --   ALLOCATION_CONFLICT — allocator bug (FR-25, INV-11); fails the run
ledger_entries(id, booking_id, entry_type, amount_paise, created_at)
  -- entry_type: CHARGE | REFUND
```

### 10.4 Waitlist and outbox

```sql
waitlist_entries(id, schedule_id, travel_class, booking_id, seq,
                 entry_type, created_at, promoted_at,
                 UNIQUE(schedule_id, travel_class, entry_type, seq))
  -- entry_type: RAC | WL
  -- seq: monotonic arrival order from a per-(schedule,class,entry_type)
  --      sequence. NEVER renumbered. Promotion sets promoted_at in place.
CREATE INDEX ON waitlist_entries (schedule_id, travel_class, entry_type, seq)
    WHERE promoted_at IS NULL;

outbox(id, aggregate_type, aggregate_id, event_type, payload JSONB,
       created_at, published_at NULL)
idempotency_keys(key PRIMARY KEY, user_id, request_hash, booking_id,
                 created_at)
  -- Stores a REFERENCE, not a frozen response (FR-19). Replay re-renders
  -- from current booking state.
checkpoints(partition_key PRIMARY KEY, kafka_offset, generation_id,
            mask_snapshot BYTEA, updated_at)   -- Strategy B only
  -- Writes are generation-guarded (§9.3): WHERE generation_id <= :myGeneration
```

**Displayed waitlist position is derived, never stored (DD-011):**

```sql
ROW_NUMBER() OVER (
    PARTITION BY schedule_id, travel_class, entry_type
    ORDER BY seq
) WHERE promoted_at IS NULL
```

FR-41 promotes out of order — "oldest entry *whose range fits*" — and age and fit are independent, so any stored contiguous position would need renumbering on nearly every promotion. Under P5 that is roughly 245,000 serialised row-writes per second on a unique index, which deadlocks rather than degrades. Contiguity is a **display** property, guaranteed by the window function; it is not a **storage** property to be defended by locking.

### 10.5 Redis key schema

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `masks:{sched}:{cls}:{pool}` | String (packed longs) | Berth masks, Strategy A | none |
| `freecount:{sched}:{cls}:{pool}` | String (packed ints) | Per-segment free counts (INV-12) | none |
| `holds:{sched}:{cls}:{pool}` | ZSET | holdId → expiry epoch ms | none |
| `hold:{holdId}` | Hash | berths, mask, bookingId | 150 s |
| `queue:{sched}:{cls}` | ZSET | Admission FIFO, score = issue ms. Bounded by FR-35a | none |
| `qtoken:{token}` | Hash | status, admittedAt, score | 300 s |
| `admitwm:{sched}:{cls}` | String | Admission watermark, published 1/s (FR-37) | none |
| `search:{train}:{date}:{from}:{to}:{cls}:{pool}` | String | Cached availability | 2 s |
| `rate:{userId}:{bucket}` | String | Per-user rate limit, two-bucket sliding window (FR-60) | 2 s |

Redis is authoritative only during the hold window. Hold **expiry** is durable in `bookings.hold_expires_at`; the Redis structures above are a working set, and C2 must be survivable by rebuild (§13.4).

### 10.6 Seed data

- **FR-48** A generator produces: 20 trains, routes of 8–25 stops, 4–8 coaches per train across 3–5 classes, schedules for 30 forward days. Roughly 300k berths total.

  It must also assign a **berth-type distribution** (`LOWER`, `MIDDLE`, `UPPER`, `SIDE_LOWER`, `SIDE_UPPER`) per coach layout. FR-38's RAC allowance is defined in terms of `side_lower_berth_count`, which is not computable without it.
- **FR-49** Three trains are designated **hot** and receive disproportionate load in profile P3.
- **FR-50** Generation is deterministic given a seed, so benchmark runs are comparable across strategies.
- **FR-69** The generator produces **≥5,000 synthetic users**, deterministically under the same seed.

  This is required by FR-59 (every booking carries a `userId`) and load-bearing for §19: FR-60 caps each user at 10 rps, so P2's 2,000 rps needs ≥200 distinct users and P1's 5,000 rps needs ≥500. Under-provisioning means the harness rate-limits itself and the benchmark measures nothing — see §19.5.

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
| API-9 | `GET /bookings/{pnr}` | Booking detail (waitlist position derived per §10.4) |
| API-10 | `GET /admin/schedules/{id}/seatmap` | Live berth occupancy for the UI |
| API-11 | `GET /actuator/prometheus` | Metrics |

### 11.1 Representative request/response

`POST /bookings/hold`

```http
Idempotency-Key: 8f3c...   (required; also serves as commandId in Strategy B)
X-Queue-Token: qt_...      (required only in queued mode)

{ "scheduleId": 4412, "travelClass": "3A", "quotaType": "TATKAL",
  "fromStationCode": "NDLS", "toStationCode": "BCT",
  "passengers": [ {"name":"...", "age":34, "gender":"M"} ] }
```

```json
{ "holdId": "h_9c21...", "bookingId": 88213, "bookingClass": "CNF",
  "status": "HELD",
  "expiresAt": "2026-09-02T10:00:47Z", "farePaise": 245000,
  "allocations": [ {"coach":"B3","berth":41,"berthType":"LOWER"} ] }
```

The response carries `status` because an idempotency replay returns the **current** representation (FR-19), which may be `EXPIRED` or `CONFIRMED` with a PNR rather than `HELD`.

### 11.2 Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `SEAT_UNAVAILABLE` | 409 | No berth free for that segment range |
| `QUOTA_LOCKED` | 409 | Tatkal window not yet open |
| `QUOTA_EXHAUSTED` | 409 | CNF + RAC + WL all full |
| `HOLD_EXPIRED` | 410 | Hold TTL elapsed |
| `QUEUE_REQUIRED` | 429 | Partition in queued mode; obtain a token |
| `QUEUE_FULL` | 429 | Projected wait exceeds the horizon; no token issued (FR-35a) |
| `QUEUE_NOT_ADMITTED` | 425 | Token valid, not yet admitted |
| `RATE_LIMITED` | 429 | Per-user rate limit |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Key matches, request payload differs (FR-19) |
| `DUPLICATE_REQUEST` | 200 | Idempotency replay; **current representation** returned |
| `RETRY_LATER` | 503 | Partition owner replaying, or reply timed out (Strategy B) |
| `CHART_PREPARED` | 409 | Booking closed for this schedule |

**FR-51** `SEAT_UNAVAILABLE` and `QUOTA_EXHAUSTED` are **correct outcomes**, not errors. They must be excluded from the NFR-7 error budget and counted separately in metrics. Conflating them is a common and revealing mistake.

`RATE_LIMITED` is **not** excluded, and is handled differently again: see §19.5. It reflects harness configuration, not system state, and its presence voids a benchmark run rather than counting against it.

`QUEUE_REQUIRED`, `QUEUE_FULL` and `RATE_LIMITED` all return 429. k6 thresholds and Grafana panels must split on the error **code**, not the status, or admission pressure and rate-limit rejection blur into one series on the demo dashboard.

---

## 12. Simulated payment service

A separate small Spring Boot service (`psp-sim`) in the same repo.

- **FR-52** `POST /psp/payments` accepts a charge and responds immediately with `INITIATED`.
- **FR-53** Settlement is asynchronous. Latency is drawn from a configurable distribution (default: log-normal, median 800 ms, p99 6 s).
- **FR-54** Configurable outcome mix: success / failure / **timeout-then-late-success** / **webhook-never-sent**. The last two exist specifically to exercise FR-23 and FR-24.
- **FR-55** Webhooks are HMAC-SHA256 signed and **deliberately delivered twice** for 5% of payments.
- **FR-56** `POST /psp/admin/chaos` reconfigures the mix at runtime, so a chaos scenario can degrade payments mid-run.
- **FR-57** `POST /psp/refunds` for FR-43, FR-24 and FR-25.

---

## 13. Failure handling

### 13.1 Failure matrix

| Failure | Detection | Response | Test |
|---|---|---|---|
| Hold expires before payment | Reaper / lazy reap | Release berths, booking → `EXPIRED` | T-C1 |
| Payment succeeds after expiry | Confirm sees expired `hold_expires_at` | Auto-refund `HOLD_EXPIRED` → `FAILED_REFUNDED` | T-C4 |
| **Allocation conflict at confirm, hold live** | `EXCLUDE` constraint rejects insert | Auto-refund `ALLOCATION_CONFLICT`; **run fails** (NFR-9, INV-11) | T-C9 |
| Webhook delivered twice | `payment_events` unique key | Second is a no-op | T-C5 |
| Webhook never delivered | Reconciliation poll (FR-23) | Poll PSP, settle | T-C6 |
| Redis lost | Connection failure | Rebuild masks **and free counts** from Postgres; in-flight holds lost | C2 |
| Postgres paused | Timeout | Reject writes with 503; reads served from cache | C3 |
| Partition owner crashes | Consumer rebalance | New owner replays from checkpoint, ≤2 s (NFR-8) | C1 |
| Split brain on rebalance | **Producer epoch fencing** | Fenced owner emits no reply; client retries | T-C7 |
| **Zombie owner overwrites checkpoint** | Generation guard on `UPDATE` | Stale checkpoint write rejected by Postgres | T-C10 |
| Kafka broker restart | Producer retry | Buffered commands retried; no double-apply | C4 |
| Concurrent cancel + promote | DB row lock on booking | Serialised; one wins | T-C8 |

### 13.2 Hold reaper

Runs every 5 s per replica. Uses a Redis lock so only one replica reaps a given partition. Reaping is also lazy inside the allocation script (§9.2), so the reaper is an optimisation for idle partitions, not a correctness dependency. **This distinction must be stated in code comments** — it is the kind of design detail a Reviewer agent should verify.

### 13.3 Outbox pattern

Domain events (`BookingConfirmed`, `BookingCancelled`, `BookingPromoted`, `ChartPrepared`) are written to `outbox` inside the same transaction as the state change, then published to Kafka by a poller. This avoids the dual-write problem between Postgres and the log.

### 13.4 Mask and free-count rebuild

`POST /admin/schedules/{id}/rebuild-state` reconstructs **both** the berth masks and the per-segment free counts — for Strategy A in Redis, for Strategy B in the owner's heap — from `seat_allocations`.

Rebuilding masks alone is insufficient and was a latent C2 failure in v1.1: after `FLUSHALL` the masks return and the free counts do not, so they read as zero and **search reports zero availability for the remainder of the run**, while C2's stated expectation (INV-8 passes) is fully satisfied. C2's acceptance must assert **INV-12** alongside INV-8.

Rebuild must be safe to run against a live partition (it acquires the partition and blocks commands with `RETRY_LATER` during rebuild).

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
| INV-7 | For every booking: `sum(CHARGE) − sum(REFUND) == expected retained fare`, where expected is **recomputed independently** per FR-67b | Ledger reconciliation |
| INV-8 | Redis masks match Postgres `seat_allocations` exactly (post-run, quiesced) | Rebuild and diff |
| INV-9 | Waitlist `seq` is unique and strictly increasing per `(schedule, class, type)`, and no active entry has `promoted_at` set | Window function over `waitlist_entries` |
| INV-10 | No booking in a terminal state has an active hold | Join |
| **INV-11** | **No refund exists with `reason = 'ALLOCATION_CONFLICT'`** | Scan `refunds` |
| **INV-12** | **Free counts match masks exactly (post-run, quiesced)** | Recompute counts from masks and diff |

INV-8 and INV-12 are the strongest checks and the ones that catch the subtle bugs. Both run **only after the system quiesces**, since during load a transient divergence is expected and legitimate.

**INV-9 changed in v1.2.** It previously required stored positions to be contiguous, which was incompatible with FR-41's out-of-order promotion under P5 (DD-011). Contiguity of the *displayed* position is now structural, guaranteed by the `ROW_NUMBER()` in §10.4.

**INV-11 and INV-12 are new.** INV-11 closes the case where a real double-charge occurs and every other invariant passes (DD-008). INV-12 closes the case where seven writers drift a denormalised counter that sits directly upstream of `allocation_conflicts_total`, the metric §9.4's conclusion rests on (DD-012).

---

## 15. Observability

### 15.1 Metrics

Business: `bookings_attempted_total{class,quota,result}`, `bookings_confirmed_total`, `seats_available{schedule,class}`, `waitlist_depth`, `promotions_total`.

Contention: `allocation_conflicts_total` — the single most important metric in this system, since it is the direct measure of contention each strategy faces. Also `allocation_attempts_per_success` (histogram), `hold_expiry_total{reason}`.

Correctness: `allocation_constraint_violations_total` (any non-zero value fails the run, NFR-9), `freecount_drift_total` (gauge, sampled at quiesce points during P4).

Latency: `allocation_duration_seconds` (core operation only), `hold_request_duration_seconds` (end to end), `payment_settle_duration_seconds`.

Strategy B: `partition_owner_count`, `partition_replay_duration_seconds`, `command_reply_latency_seconds`, `consumer_lag`, `stale_generation_events_total`, `producer_fenced_total`, `dedup_evictions_before_window_expiry_total`, `orphaned_replies_total`, `checkpoint_lag_offsets`.

Admission: `queue_depth{schedule,class}`, `admission_rate`, `queue_wait_seconds`, `tokens_expired_total`, `queue_full_rejections_total`.

Harness validity: `rate_limited_total` — see §19.5.

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
- **FR-60** Per-user rate limit: 10 requests/second, implemented as a **two-bucket sliding-window counter** in Redis (previous bucket weighted by elapsed fraction). Returns `RATE_LIMITED`.

  A single counter key with a 1-second TTL is a *fixed* window, not a sliding one, and permits a clean 2× burst across the boundary. A true per-request ZSET of timestamps would be exact but costs O(log N) and real memory per user on the hot path at 5,000 users; the two-bucket approximation is the middle option.

  **FR-60 is tested by a dedicated integration test** with a single user and a tight loop — never via a load profile, where it must never bind (§19.5).
- **FR-61** PSP webhooks verify HMAC-SHA256 with constant-time comparison; unsigned or mis-signed webhooks are rejected and counted.
- **FR-62** No PII beyond synthetic passenger names. No card data ever touches the system — the PSP simulator handles the notional payment instrument.

---

## 17. Frontend — React dashboard

Purpose: make the system's behaviour *visible* during a spike. This is the demo asset and the source of README screenshots and a recording. It is not a consumer product.

### 17.1 Views

**V-1 Booking flow.** Search → select train/class → passenger form → hold (with a live countdown on the hold TTL) → payment (mock button) → PNR. The hold countdown is the important detail; it makes the two-phase nature of the system legible at a glance.

**V-2 Live seat map.** Grid of berths for a `(schedule, class)`, each cell showing its segment mask as a small horizontal bar of `N-1` cells — filled where occupied. Polls `API-10` every 500 ms. During a spike this visibly fills in. **This is the single best screenshot the project can produce** and should be built well; it renders the core abstraction of §5.2 directly on screen.

**V-3 Queue position.** SSE-driven. Position, people ahead, estimated wait, admission countdown. Position is computed client-side from the token's own score and the broadcast watermark (FR-37) and must be labelled **approximate**.

**V-4 Ops panel.** Embedded Grafana panels plus buttons to trigger chaos scenarios and chart preparation, so a demo can be driven end to end from one screen.

### 17.2 Constraints

- **FR-63** React 18 + Vite + TypeScript. State via TanStack Query; no Redux.
- **FR-64** No component library required — the seat map is custom SVG or CSS grid regardless.
- **FR-65** The UI must degrade rather than fail when the backend returns `QUEUE_REQUIRED`, `QUEUE_FULL` or `RETRY_LATER`; those states have designed screens, not error toasts.
- **FR-66** Visual design is delegated to the implementing agent. Constraint: dark background, high contrast, dense information — it is a control panel, not a marketing page.

---

## 18. Testing strategy

| Layer | Scope | Tooling |
|---|---|---|
| Unit | Segment mask algebra, allocation, fare/refund tiers, state machine, PNR check digit | JUnit 5 |
| Property-based | Mask invariants under random operation sequences | `PropertyRunner` (seeded loop + shrinking, `domain/src/test`) |
| **Differential** | **Java reference vs. Lua script, step by step (T-7)** | **`PropertyRunner` + Testcontainers Redis**, in `differential/` |
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
- **T-4** *Property.* For any random sequence of allocate/release operations, `INV-1` holds and released capacity always returns exactly to baseline. Must include a case exercising **segment 63** (FR-3a).
- **T-5** *Idempotency.* The same `Idempotency-Key` sent 100 times concurrently produces exactly one allocation.

  The mechanism is specified, not left open: the key row is inserted first under its primary key, in a transaction, **before** allocating; the 99 losers resolve to the winner's `bookingId` on the unique-constraint conflict. Written as check-then-act this test is intermittently flaky in a way that reads as a load-test artifact.
- **T-6** *Chart promotion.* Given `n` CNF, `m` RAC, `k` WL, cancelling `j` CNF bookings promotes **at most** `j` RAC → CNF and `j` WL → RAC, and `INV-9` holds throughout.

  "Exactly `j`" is false in general, because a cancelled range may fit no waiting entry (FR-41). Either pin the fixture to identical ranges — in which case exactly `j` is assertable and should be — or assert the bound with the fit condition stated.
- **T-7** *Differential allocator equivalence.* `PropertyRunner` generates random sequences of allocate / release / reap operations from a fixed seed, shrinking any failure to a minimal diverging sequence. Each sequence runs against **both** the Java reference in `domain/inventory` and the real `allocate.lua` on a Testcontainers Redis. After **every step**, assert identical returned results **and** identical mask and free-count state. Any divergence fails the build.

  This test carries the weight that v1.1 mistakenly assigned to shared code (§9.1, DD-001). It is a Phase 1 gate.

Coverage target: **≥85% on `domain/`**, no target elsewhere. Chasing coverage in adapters produces test theatre.

---

## 19. Load and chaos plan

### 19.1 Profiles

| ID | Profile | Shape | Purpose |
|---|---|---|---|
| P1 | Tatkal spike | 0 → 5,000 VU arrival rate over 10 s, hold 30 s, ramp down | NFR-2, the headline scenario |
| P2 | Sustained mixed | 2,000 rps, 90% search / 10% book, 10 min | NFR-1, NFR-3, NFR-5 |
| P3 | Hot partition | 100% of P1 load onto **one** `(train, date, class)` | The strategy discriminator |
| P4 | Soak | 500 rps, 30 min | Leak and drift detection; INV-5, INV-12 (`freecount_drift_total` sampled at quiesce points) |
| P5 | Cancellation storm | 1,000 rps cancels against a fully booked train | Promotion under load (FR-41), INV-9 |

**User mapping is part of the profile, not an implementation detail.** One k6 virtual user maps to **one distinct synthetic user** (FR-69), across every profile. At P1's 5,000 VUs that is 1:1, so neither FR-60's 10 rps cap nor FR-20's 3-hold limit binds during benchmarks. See §19.5.

P1 targets a schedule whose Tatkal window is open under the injected `Clock` (FR-31), so the profile's name describes what it actually exercises.

**Profile magnitudes are calibrated, not fixed (DD-019).** The VU and rps figures above are v1.2's 16 GB reference values. P1's spike arrival rate is NFR-2 and P2's sustained rate is NFR-1, both of which AC-1.13 sets from measurement on the real machine (bounded above by AC-0.7's Phase 0 ceiling); P3, P4 and P5 scale from them by the same ratios (P3 = P1 concentrated on one partition, P4 = 25% of P2 for 30 min, P5 = 50% of P2 as cancels). What does **not** scale is the 1 VU : 1 synthetic user rule or §19.5's validity gate — a smaller spike with a valid run is a result; a larger one with `RATE_LIMITED > 0` is not.

### 19.2 Chaos overlays

| ID | Injection | Applied during | Expectation |
|---|---|---|---|
| C1 | `docker kill` one app replica at spike peak | P1 | Strategy B: ≤2 s warm recovery (NFR-8), zero lost confirmed bookings, no double-allocation (`dedup_evictions_before_window_expiry_total` = 0). Strategy A: near-zero impact |
| C2 | `redis-cli FLUSHALL` mid-run | P2 | In-flight holds lost; confirmed bookings intact; masks **and free counts** rebuilt; **INV-8 and INV-12** pass after rebuild; FR-24 decisions unaffected (they read `hold_expires_at`) |
| C3 | Toxiproxy: 5 s Postgres freeze | P2 | Writes 503, reads served, no data corruption, recovery on release |
| C4 | Kafka broker restart | P1 | Strategy B buffers and retries; no double-apply (INV-1); checkpoints survive in Postgres |
| C5 | PSP → 50% timeouts, 20% late-success | P2 | Holds expire correctly; late successes auto-refund with reason `HOLD_EXPIRED` (FR-24); INV-3 passes; **zero** `ALLOCATION_CONFLICT` refunds (INV-11) |

### 19.3 Reporting

`loadtest/report-generator` emits a Markdown report per run containing: profile, strategy, git SHA, hardware, **JDK build**, container resource limits, throughput, latency table, error breakdown split into *legitimate* vs *failure* (FR-51), invariant results, and Grafana screenshots. Reports are committed under `docs/benchmarks/`.

### 19.4 Honesty requirements

Non-negotiable, and stated in the README:

- **NFR-12** Every reported number carries: hardware spec, JDK build, container CPU/memory limits, whether the load generator was co-located, and the run duration.
- **NFR-13** The report states plainly that k6 and the system under test share a laptop, and that headroom on dedicated infrastructure would be higher **without estimating how much higher**.
- **NFR-14** Failed runs and violated invariants stay in `docs/benchmarks/`. The history of what broke and how it was fixed is the most interesting document in the repository, and deleting it is the single fastest way to make the project look fabricated.

### 19.5 Run validity

A benchmark run is **valid** only if all of the following hold. An invalid run must not produce a report at all — the generator refuses, rather than emitting a caveated number.

- **`rate_limited_total == 0`.** `RATE_LIMITED` during a load test reflects **harness configuration**, not system state: there was no shortage of anything except synthetic users. Unlike `SEAT_UNAVAILABLE`, which reports a real property of the inventory, it means requests were rejected at the edge before reaching the system under test. Reporting throughput from such a run publishes a number where a large fraction of the load was never served (DD-017).
- **`allocation_constraint_violations_total == 0`** (NFR-9).
- All §14 invariants pass.

This sits alongside §19.4 rather than beside it: NFR-13 promises the report states honestly what was measured, and an under-provisioned harness defeats that promise silently, below the layer anyone inspects.

---

## 20. Delivery phases

Each phase is independently shippable. If time runs out, you stop at a phase boundary with something complete.

**Phase 2 is the finish line** (DD-018). §1's engineering claim requires *two* concurrency strategies whose trade-offs were measured; Phase 1 ships one. A one-strategy system with good invariants is a decent project, but it is not the project this document describes, and everything distinctive lives in the comparison. Phase 3 adds domain depth and the demo surface, and is in scope.

### Phase 0 — Foundation

Repo skeleton, Maven multi-module reactor, Docker Compose stack, Flyway schema, seed generator, health endpoints, ArchUnit module rules, CI running unit tests.

- **AC-0.1** `docker compose up` brings the full stack healthy in ≤120 s (NFR-10), Kafka included, with a healthcheck that waits for broker readiness rather than a fixed sleep.
- **AC-0.2** Seed generator produces the §10.6 dataset deterministically in ≤60 s, including berth types (FR-48) and ≥5,000 users (FR-69).
- **AC-0.3** All §10 tables created including the `EXCLUDE` constraint and the partial index on `waitlist_entries`.
- **AC-0.4** ArchUnit fails the build on a `domain → adapters` dependency. Note that under Maven this is *also* a compile error (§8.2); ArchUnit's job is the transitive cases.
- **AC-0.5** `docs/design-decisions.md` carries entries for every Phase 0 choice — Kafka partition count, connection pool sizing, seed data shape, module boundaries (DOC-1, DOC-3). DD-001…DD-018 are already present from the v1.1 review.
- **AC-0.6** Toolchain spike passes: ArchUnit parses `--enable-preview` bytecode from `adapters/web`, and the Java 25 `StructuredTaskScope.open(Joiner...)` shape compiles and runs (§8.5, DD-004). If it fails, fall back to a virtual-thread executor and supersede DD-004 before proceeding.
- **AC-0.7** **Hardware calibration gate (DD-019).** Two measurements, neither of which requires the booking endpoints to exist:
  1. **Memory.** A committed `.wslconfig` pins the WSL2 memory ceiling, and `docker stats` over a 5-minute idle full stack shows total resident memory within NFR-11's 4.5 GB.
  2. **HTTP ceiling.** k6 ramps against a trivial `GET /actuator/health` on the real two-replica-behind-nginx topology until p99 breaches 50 ms. This measures the box's *hardware and framework* ceiling with **no domain work in the path** — an upper bound that NFR-1 and NFR-2 can never exceed, obtainable before a single business rule is written.

  Committed as `docs/benchmarks/000-calibration.md` with the full NFR-12 metadata block.

  **Floor: 400 req/s on the no-I/O endpoint.** Below that, escalate to OQ-2 before starting Phase 1. The number is derived rather than picked: P1 must exhaust a single train's inventory (~500 berths in a class) inside a 30-second spike, which needs at least ~17 successful holds/s, and a spike whose *point* is contention needs roughly an order of magnitude more attempts than successes. A no-I/O ceiling under 400 rps cannot supply that once real endpoint work is subtracted. **Measured 2026-09-04: 750 rps** (500 rps with one backend round trip) — passes (DD-029).

  NFR-1 and NFR-2 themselves are set later, by AC-1.13, once `search` and `hold` exist. **AC-1.13 carries the question this gate could not answer:** whether P3 generates enough contention for §9.4 to discriminate the two allocators. A health-check rate cannot establish that; only `hold` under load can.
  *(k6 is not currently installed on the build machine; installing it is part of this criterion.)*

### Phase 1 — Core booking path (MVP, shippable)

Segment-mask domain, Strategy A, Tatkal window, hold/pay/confirm/cancel, PSP simulator, PNR, fare, invariant checker, k6 P1 + P2, Grafana dashboards 1 and 3.

Internal checkpoints, so a four-to-six-week phase has structure:

- **1a** — `domain/` + Strategy A + T-1…T-4 + T-7. No HTTP.
- **1b** — Full lifecycle over REST + PSP simulator.
- **1c** — Invariants + k6 + dashboards + report generator.

Acceptance criteria (renumbered contiguously; v1.1 had no AC-1.7):

- **AC-1.1** T-1, T-2, T-3, T-4, T-5 all pass.
- **AC-1.2** P1 completes with zero INV violations and zero `allocation_constraint_violations_total`.
- **AC-1.3** P2 meets NFR-1, NFR-3, NFR-5, and is a **valid** run under §19.5.
- **AC-1.4** All twelve invariants have executable checks; all pass. INV-7 recomputes expected fare independently (FR-67b).
- **AC-1.5** C5 (PSP chaos) passes: no orphaned payments, no orphaned holds, zero `ALLOCATION_CONFLICT` refunds.
- **AC-1.6** Allocator contract test suite exists and Strategy A passes it.
- **AC-1.7** **T-7 passes.** The Lua implementation is proven step-for-step equivalent to the `domain/inventory` reference, including free-count state (DD-001).
- **AC-1.8** Overlapping-insert test proves the DB constraint rejects it, and T-C9 proves a live-hold conflict routes to `FAILED_REFUNDED` with reason `ALLOCATION_CONFLICT`.
- **AC-1.9** Dashboards provisioned as code.
- **AC-1.10** Benchmark report for P1 and P2 committed.
- **AC-1.11** Tatkal unlock is clock-driven and testable via injected `Clock` (FR-28…FR-31, moved here from Phase 3 by DD-018). P1 runs against an open Tatkal window.
- **AC-1.12** Every Phase 1 decision meeting the DOC-2 bar has a log entry, each with at least two rejected alternatives (DOC-4) and a falsifiable revisit condition (DOC-5). Reviewer agent verifies log-to-code consistency (DOC-6).
- **AC-1.13** **NFR-1 and NFR-2 are set by measurement (DD-019), at checkpoint 1c.** With `search` and `hold` live, k6 ramps each until p99 breaches NFR-5 (50 ms) and NFR-3 (150 ms) respectively; those rates are written into the §7 table as NFR-1 and NFR-2, and §19.1's profile magnitudes derive from them. Committed as `docs/benchmarks/001-nfr-calibration.md`. Compare against AC-0.7's health-endpoint ceiling and state the ratio: it is the cost of the domain path, and explaining it is a §9.4 input. AC-1.3's judgement of P2 is made against these measured numbers, not v1.2's 16 GB reference figures.

### Phase 2 — Second strategy and the comparison

Strategy B (partitioned single-writer), WAL, transactional fencing, checkpointing, replay, chaos suite C1–C4, P3 hot-partition profile, comparison report, Grafana dashboard 2.

- **AC-2.1** Strategy B passes the identical contract suite (AC-1.6) with no test modifications.
- **AC-2.2** Owner crash at spike peak recovers in ≤2 s (NFR-8) with zero lost confirmed bookings. Cold-start replay of 100k events completes in ≤10 s (NFR-15).
- **AC-2.3** P3 run for both strategies; results differ measurably and the difference is explained mechanistically, not hand-waved. The threats to validity in §9.4 are stated in the report.
- **AC-2.4** Split-brain test T-C7 proves the fenced owner **produced no reply at all** (DD-006). T-C10 proves a zombie owner's checkpoint write is rejected by the generation guard (DD-013).
- **AC-2.5** Comparison report published with a "when to choose which" conclusion.
- **AC-2.6** Zero invariant violations across every chaos scenario, both strategies. `dedup_evictions_before_window_expiry_total` is zero under P1 and P3.
- **AC-2.7** Design decision log covers the Strategy B mechanics specifically: checkpoint interval, replay buffering, transactional fencing, reply-topic partitioning, and the size and retention of the command dedup/reply cache. The §9.4 comparison conclusion is promoted to an ADR (DOC-7).

*At this point the project supports the claim in §1 and the résumé entry in §22. Everything after this makes it stronger.*

### Phase 3a — Domain depth

RAC/WL, promotion, chart preparation, P5.

- **AC-3a.1** T-6 passes; INV-9 holds under P5, with cancellation writing two rows rather than renumbering the queue tail (DD-011).
- **AC-3a.2** Chart preparation merges unsold TATKAL into GENERAL (FR-42 a.5), promotes to exhaustion, and refunds remaining WL.
- **AC-3a.3** API-9 renders derived waitlist position within NFR-3's latency budget with a full waitlist under P5.
- **AC-3a.4** Design decision log covers RAC/WL caps, promotion ordering, and chart-time semantics.

### Phase 3b — Demo surface

Admission control queue, React dashboard.

- **AC-3b.1** Admission controller keeps **unexpired admitted tokens ≤ 2 × remaining_berths at every 1-second tick** during P1 (DD-015). Hysteresis prevents mode flapping at the threshold.
- **AC-3b.2** Queue position is delivered by watermark broadcast; Redis operations attributable to queue display do not appear in allocation latency (FR-37, DD-016).
- **AC-3b.3** Live seat map renders a P1 spike in real time without dropping frames.
- **AC-3b.4** A recorded demo shows: queue → admission → booking → seat map filling → chaos injection → recovery.
- **AC-3b.5** Design decision log covers the admission rate formula and its parameters. Any decision reversed during Phases 1–3 has a superseding entry with evidence (DOC-8).

### Sizing

Competent solo developer with strong AI assistance, full-time:

| Phase | Estimate |
|---|---|
| Phase 0 | 1–2 weeks |
| Phase 1 | 4–6 weeks |
| Phase 2 | 4–6 weeks |
| *Minimum defensible artifact* | *9–14 weeks* |
| Phase 3a | 2–3 weeks |
| Phase 3b | 3–4 weeks |
| **Full scope** | **14–21 weeks** |

Evenings and weekends: roughly triple. Phase 2 is the phase most often underestimated — transactional producers, rebalance handling, replay with command buffering, fencing across both Kafka *and* Postgres, and a reply cache that survives owner death is not "a second implementation of an interface."

If Phase 2 overruns its upper bound by more than half, **cut 3b before 3a**. The dashboard is a demo asset; RAC/WL is domain substance and is what an interviewer will probe.

---

## 21. Repository conventions

- Conventional commits, with the requirement ID in the body (`Implements: FR-16, INV-1`).
- ADRs in `docs/adr/` for: modular monolith over microservices, the two allocator strategies, single-broker Kafka in KRaft mode and its accepted SPOF, the `EXCLUDE` constraint, approximate search, and producer-epoch fencing for partition ownership.
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

**Context.** (the problem, explained from scratch — a reader should not need
this SDD open to follow it)

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

- **DOC-10** Where an entry claims a consequence that requires a number, the number must be **measured**, not estimated. Until it is measured, the entry names the specific metric and load profile that will supply it. `docs/design-decisions.md` carries several such placeholders from the v1.1 review; filling them is part of the phase that implements the decision.

---

## 22. Resume framing

For the two-line project entry, once Phase 2 is complete:

> **TatkalRush** — Railway reservation engine handling segment-wise seat inventory under Tatkal-style demand spikes. Implemented two interchangeable concurrency strategies — Redis-Lua atomic allocation and a Kafka-partitioned single-writer with WAL recovery and producer-epoch fencing — and benchmarked them head-to-head at 5k req/s peak on a single machine. Twelve machine-checked invariants (zero overbooking, zero double-charge) verified after every load and chaos run; Postgres GiST exclusion constraints make overlapping allocations structurally impossible.

What makes this defensible in an interview is not the throughput number. It is that you can answer "why did you pick that design?" with a measurement instead of an opinion, and "what breaks?" with a chaos report instead of a guess.

---

## 23. Open questions register

Agents that encounter genuine ambiguity append here with the requirement ID, the ambiguity, and the options considered, and escalate rather than guess.

This register is a working instrument, not a scoreboard. v1.1 claimed it was empty because "all design decisions were resolved before drafting"; the design review of 2026-09-04 found seven correctness holes and three undefined subsystems, so that claim is withdrawn. An empty register means nobody is looking hard enough.

| ID | Section | Question | Raised by | Status |
|---|---|---|---|---|
| OQ-1 | §20 | Calendar budget — determines whether Phase 3a/3b are in scope | design review 2026-09-04 | **RESOLVED** — Phase 3 in scope (DD-018) |
| OQ-3 | §8.2 | Which module is the composition root? v1.2's tree names `adapters/web` as the HTTP layer but never says what assembles the deployable, and putting the main class in `adapters/web` would force it to depend on the other adapters, breaking the layering rule ArchUnit enforces. Phase 0 added an `app/` module rather than guess silently | Phase 0 Task 2, 2026-09-04 | **OPEN** — confirm or relocate |
| OQ-2 | §7, §19 | What throughput does the 7.9 GB / 8-CPU build machine actually sustain? | hardware recalibration 2026-09-04 | **RESOLVED** 2026-09-04 — 750 rps no-I/O, 500 rps with a backend hop (`docs/benchmarks/000-calibration.md`). Threshold replaced with a derived 400 rps floor; passes. The *separate* question of whether P3 can discriminate the two allocators moves to AC-1.13 (DD-029) |

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

This is the **reference specification**. `domain/inventory` implements it in Java, and Strategy B's partition owner calls that implementation directly on its single consumer thread.

Strategy A implements the same algorithm **independently, in Lua**, executing inside the Redis process — it cannot call the Java code (§8.2, §9.1). The two are proven equivalent step-for-step by **T-7**, not by shared code. That test is what makes §9.4 a controlled comparison rather than a comparison of two different programs, and it is why T-7 is a Phase 1 gate rather than a nice-to-have.

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

## Appendix C — Change provenance

Every substantive change in v1.2 traces to a numbered decision in `docs/design-decisions.md`:

| SDD change | Decision |
|---|---|
| §8.2, §9.1, §9.2, App. A — algorithm specified once, implemented twice; T-7 | DD-001 |
| FR-3a — Lua 5.1 mask representation | DD-002 |
| §8.4 — Java 25 / Spring Boot 4.0.x pinned by digest | DD-003 |
| §8.5, AC-0.6 — structured concurrency quarantined | DD-004 |
| §8.2 — Maven reactor | DD-005 |
| §9.3, T-C7, AC-2.4 — producer-epoch fencing | DD-006 |
| §9.3 — revoked owner destroys in-memory masks | DD-007 |
| FR-25, §10.2, §10.3, INV-11, NFR-9 — constraint trip is a build failure | DD-008 |
| §9.3 — `commandId` = `Idempotency-Key`; reply cache | DD-009 |
| FR-19, §10.4, §11.2, T-5 — idempotency stores a reference | DD-010 |
| FR-41, §10.4, INV-9, T-6 — derived waitlist position | DD-011 |
| FR-13, FR-14, §13.4, INV-12, P4 — free-count integrity | DD-012 |
| §9.3, NFR-8, NFR-15, T-C10 — async, generation-guarded checkpoints | DD-013 |
| §6.9 (FR-67, FR-67a, FR-67b, FR-68), INV-7 — fare | DD-014 |
| FR-32, FR-32a, FR-35, FR-35a, AC-3b.1 — admission control parameters | DD-015 |
| FR-34, FR-37, AC-3b.2 — watermark broadcast | DD-016 |
| FR-69, FR-60, §19.1, §19.5 — synthetic users; run validity | DD-017 |
| §20, FR-28…FR-31, §1 — Phase 2 is the finish line; phase restructuring | DD-018 |
| §7 (NFR-1, NFR-2, NFR-11), §8.3, §8.4, §19.1, AC-0.7, OQ-2 — hardware recalibration (v1.2.1) | DD-019 |
