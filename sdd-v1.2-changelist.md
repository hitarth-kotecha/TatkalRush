# TatkalRush SDD — v1.1 → v1.2 change register

**Source:** grilling session, 2026-09-04
**Status:** all items accepted by author; pending application to `tatkal-rush-sdd-v1.1.md`
**Open:** none. OQ-1 (calendar budget) resolved 2026-09-04 — Phase 3 is in scope.
**Companion:** `docs/design-decisions.md` (DD-001…DD-018) records the reasoning behind each item.

This is a spec-revision list, not a design-decision log. `docs/design-decisions.md` (DOC-1) is written by the implementing agents; this document tells them what the spec now says.

---

## A. Corrections to claims that were false as written

**A1 — §8.2 line 306, Appendix A line 981: the shared-code claim.**
Strategy A executes inside Redis as Lua and *cannot* call `domain/inventory`. Delete "Both allocator strategies call into it" and "byte-for-byte the same". Replace with: the algorithm is specified once in `domain/inventory` and implemented twice — Java for Strategy B, Lua for Strategy A — with equivalence proven by differential test, not shared code.

**A2 — new T-7, Phase 1, gates AC-1.6.**
jqwik generates random allocate/release/reap sequences; run against (a) the Java reference and (b) the real Lua script on Testcontainers Redis; assert identical results *and* identical final mask state after every step.

**A3 — FR-3, the 64-segment bound.**
Redis embeds Lua 5.1: all numbers are doubles, no native 64-bit integers. Either lower the documented bound to 32 segments with the reason stated, or mandate `bit.band`/`bit.bor` with the mask split across two 32-bit halves plus a property test at segment 63.

**A4 — §8.4 technology table.**
Java 25 pinned by image digest (preview classfiles are version-locked and every committed benchmark depends on it). Spring Boot **4.0.x** — 3.4 targets Java 17–23 and will not run on 25. Remove the `+`. Add JDK build to NFR-12's reported metadata.

**A5 — §8.5 structured concurrency.**
`StructuredTaskScope` is preview in Java 25 (JEP 505, 5th preview); scoped values are final (JEP 506). Retained by author decision. `--enable-preview` confined to one Maven module, never `domain/`. Note in §8.5 that the API was **rewritten** in the 25 preview — `StructuredTaskScope.open(Joiner.allSuccessfulOrThrow())`, not the 21–23 `ShutdownOnFailure` / `fork` / `join` / `throwIfFailed` shape. Phase 0 spike proves ArchUnit and jqwik parse preview bytecode before anything is built on it.

**A6 — build tool: Maven reactor, not Gradle** (§8.2, §8.4).
Import `spring-boot-dependencies` as a BOM into a custom root POM; do **not** inherit `spring-boot-starter-parent` per module. `ui/` and `loadtest/` leave the reactor and build independently. `maven-enforcer-plugin` on `domain/` for dependency convergence and banned transitives. `mvn dependency:go-offline` as a Docker layer before source copy, or NFR-10 is dominated by dependency resolution.

Note: `--enable-preview` must appear in **three** places — compiler plugin `<compilerArgs>`, surefire `<argLine>`, and Dockerfile runtime args. Missing the surefire one fails as an opaque `UnsupportedClassVersionError`.

Upside worth recording: under Maven the inward-only dependency rule (§8.2) becomes a **compile error**, not just an ArchUnit assertion — a module that does not declare `adapters` cannot resolve adapter classes at all. AC-0.4 gets stronger, with ArchUnit demoted to catching transitive leaks.

---

## B. Correctness holes closed

**B1 — §9.3 split-brain fencing moves to produce time.**

Generation-ID-at-projection fences the database *after* the client was already told OK, so a fenced owner can reply success on a berth it does not own and the client proceeds to pay. Replace with:

- Per-partition `transactional.id`; a new owner calls `initTransactions()`, bumping the producer epoch and fencing its predecessor at the broker
- The owner wraps the `booking-events` append and the `booking-replies` publish in **one transaction**; reply consumers use `isolation.level=read_committed`
- A fenced owner gets `ProducerFencedException` and **emits no reply** — the future times out into the existing `RETRY_LATER` path, and the client retries with the same `Idempotency-Key`
- Generation ID retained as defence-in-depth, demoted from primary mitigation
- Amortise commits at one transaction per consumed batch; record `read_committed` LSO lag against `command_reply_latency_seconds`

**New mandatory rule:** on `onPartitionsRevoked` / `onPartitionsLost` the owner **destroys its in-memory `long[]`** and may not serve that partition again without checkpoint-load plus replay. It applied commands that never committed; its heap state is unsound.

T-C7 rewritten: assert the fenced owner produced **no reply at all**, not that stale events were rejected downstream.

**B2 — §10.2 EXCLUDE constraint gets a failure handler.**

Confirmation order is now specified: validate hold is live → insert allocations → commit. If the insert conflicts *with a live hold*, that is unambiguously an allocator bug — a correct allocator can never trip this constraint.

- New §6.4 edge: `PAYMENT_PENDING --allocation conflict at confirm--> FAILED_REFUNDED`
- Distinguish via the existing `refunds.reason`: `ALLOCATION_CONFLICT` vs `HOLD_EXPIRED`
- New metric `allocation_constraint_violations_total`
- **NFR-9 amended:** "invariant violations *and* allocation-constraint violations: 0, non-negotiable"
- **INV-11:** no refund exists with `reason='ALLOCATION_CONFLICT'`

Rationale to keep: without this the constraint converts a data bug into a money bug while every invariant still reports green.

**B3 — §9.3 command idempotency unified with FR-19.**

`commandId` **is** the `Idempotency-Key` (or a stable hash of it) — one identity edge-to-owner.

- The owner's dedup structure changes from `Set<commandId>` to `Map<commandId, Reply>` and **re-publishes the cached reply** on a duplicate
- The cache is rebuildable from the WAL during the replay the owner already performs
- Evict by time (~60 s, see B4) with a size cap as backstop; meter `dedup_evictions_before_window_expiry_total`
- New counter `orphaned_replies_total` for replies consumed with no local future

Without this, a `docker kill` at spike peak (C1) makes the client retry with a fresh `commandId` and allocate a **second** set of berths, orphaning the first for a full 120 s TTL.

**B4 — FR-19 stores a reference, not a response.**

A frozen 200 replayed at t=300 s asserts a hold that expired at t=120 s.

- `idempotency_keys` stores `key → bookingId`; drop the `response JSONB` column
- Replay re-renders from **current** booking state (EXPIRED, or CONFIRMED with PNR)
- §11.2 `DUPLICATE_REQUEST` row: "**current representation** returned"
- Same key with a different `request_hash` → **409 `IDEMPOTENCY_KEY_REUSED`** (new, §11.2)
- **T-5 mechanism specified:** insert the key row first under its PK, in a transaction, *before* allocating; the losers resolve to the winner's `bookingId`. Check-then-act will be flaky in a way that reads as a load artifact.

Consequence: the B3 owner cache needs only ~60 s of retention, not 10 minutes — roughly 5k entries on the P1 hot partition instead of 150k.

**B5 — waitlist position becomes derived (fixes AC-3.3, unachievable as written).**

FR-41 promotes out of order ("oldest whose range fits"); INV-9 demands stored contiguity; together they force an O(n) locked renumber per cancellation. At P5's 1,000 rps against a ~700-berth class that is roughly 245k serialised row-writes/sec on a unique index. It deadlocks; it does not merely slow.

- `position` → monotonic `seq`; never renumbered; promotion sets `promoted_at`
- Position is `ROW_NUMBER() OVER (PARTITION BY schedule_id, travel_class, entry_type ORDER BY seq) WHERE promoted_at IS NULL`
- **INV-9 rewritten:** `seq` unique and strictly increasing per partition; no active entry has `promoted_at` set
- Schema: drop `UNIQUE(..., position)`; add `UNIQUE(..., seq)` and a partial index on `(schedule_id, travel_class, entry_type, seq) WHERE promoted_at IS NULL`
- FR-41 becomes one indexed `ORDER BY seq LIMIT 1` with a range predicate
- Cancellation writes 2 rows, not ~245
- **T-6 restated:** "cancelling j CNF promotes exactly j RAC→CNF" is false in general, since cancelled ranges may fit no waiting entry. Pin the fixture to identical ranges, or assert `<= j` with the fit condition stated
- FR-38's `2 x side_lower_berth_count` documented as a **policy dial**, not a physical constraint (FR-40 removed berth sharing). FR-48 must specify a berth-type distribution or the number is not computable from seed data

**B6 — INV-12: free-count integrity.**

Seven writers mutate `freecount:` (allocate, lazy reap, background reaper, release, cancel, promote, chart) and no invariant checks it. Drift high inflates `SEAT_UNAVAILABLE`, which FR-51 excludes from the error budget — so drift manufactures signal indistinguishable from real contention, corrupting `allocation_conflicts_total` and therefore §9.4's conclusion.

- **INV-12:** free counts match masks exactly, post-run and quiesced
- **§13.4 rebuilds counts, not just masks.** As written, after C2's `FLUSHALL` masks return and counts do not — search reports zero availability for the rest of the run while INV-8 passes. C2's acceptance must assert INV-12
- Initialisation specified: seeded to `pool.berth_count` per segment at schedule creation, in the same script that seeds masks
- P4 samples a `freecount_drift_total` gauge at quiesce points (P4 currently names only INV-5)

**B7 — §9.3 checkpointing.**

"Every 5 s or 1,000 events" fires every 200 ms at P1 and, unstated as async, puts a synchronous Postgres write on the single consumer thread — contradicting §9.3's own "no transactions on the hot path".

- Checkpoint writes move **off** the consumer thread: copy the `long[]` (~5.6 KB), offset and generation to a separate writer. Copy, do not share, or the snapshot tears mid-mutation
- **Generation guard:** `UPDATE ... WHERE generation_id <= :myGeneration`, and load by highest `generation_id`. Kafka's producer fencing cannot stop a zombie owner writing to *Postgres*; without this its stale checkpoint overwrites the good one and the next owner replays from a wrong offset, with no error anywhere and INV-8 only noticing post-run
- **NFR-8 split:** cold start with no checkpoint, 100k events in topic → ≤10 s; warm rebalance from a valid checkpoint → ≤2 s. As written, a 1,000-event checkpoint interval makes the 100k replay unreachable, so NFR-8 tested nothing
- Trigger retuned to 5 s / 10,000 events now that it is async; the DD entry records the tension against NFR-8's warm number
- Checkpoint the last **committed** transactional offset, never ahead of the durable WAL

---

## C. Gaps filled

**C1 — fare was never defined** (blocks INV-7, therefore AC-1.4).

`domain/pricing/` exists, FR-44 refunds a percentage of fare, §11.1 returns a fare, `bookings.fare_paise` is a column — and no requirement says what fare *is*.

- **FR-67:** `fare_paise = ceil(distance_km × class_rate_paise_per_km) + class_base_paise`, distance summed over `train_stops.distance_km` across `[from_seq, to_seq)`, per-class rates a checked-in constant table. Pure function in `domain/pricing/`
- **FR-68:** TATKAL adds a flat per-passenger surcharge by class, giving FR-45 something to bite on
- Fare computed **once at hold time**, frozen onto `bookings.fare_paise`, never recomputed — otherwise a rate-table edit breaks INV-7 across all history
- **INV-7 recomputes independently** from `(distance, class, quota, cancelled_at, departure_time)`, never from the stored `fare_paise`, or the check is a tautology that passes while pricing is wrong
- §11.1: `fareePaise` → `farePaise`

**C2 — admission control (FR-32–37): three undefined terms, no hysteresis.**

- `remaining_berths` := `min(free_count[i])` over all route segments — the count allocatable for *any* range. Single conservative scalar, O(24), degrades in the safe direction
- `expected_conversion_time_s` := config, default 30 s; FR-36's 60 s is the hard ceiling. DOC-5 revisit condition: replace with the measured p50 of admission→hold
- Rate measurement := per-replica sliding window, threshold divided by replica count. Rejected alternative for DOC-4: a Redis token bucket, which adds a round trip to the hot path during the exact spike the component exists to survive
- **Hysteresis:** enter queued mode above `threshold_rps`; leave only after a sustained 10 s below `0.5 × threshold_rps`. Without it the partition flaps at the boundary and poisons P1
- **AC-3.2 rewritten** — it was not evaluable, being a cumulative count over a denominator that reaches zero: "at every 1-second tick during P1, unexpired admitted tokens ≤ 2 × remaining_berths"
- **SSE fan-out fixed (FR-37):** broadcast an admitted-position watermark once per second; clients derive position as `my_score − watermark`. Per-user `ZRANK` at P1 queue depth is ~150k O(log N) calls/sec against the same Redis the allocator needs, and would present as "Strategy A is slow under spike"
- **`QUEUE_FULL` added to §11.2.** The queue ZSET has no TTL and no bound; with ~100 berths left and ~3/s admission, a 150k queue is a 14-hour wait. Reject once projected wait exceeds a configured horizon

**C3 — synthetic users did not exist, and the rate limiter voids every benchmark.**

FR-60 caps 10 rps per user. P2 needs ≥200 distinct users, P1 ≥500. §10.6 specifies zero users. Under-provisioning produces `RATE_LIMITED` — which FR-51 does **not** exclude from NFR-7 — so the run either fails at ~50% error rate looking like a system defect, or an agent adds it to the exclusion list and produces a green 2,000 rps number where half the traffic was never served. That number is the one that reaches `docs/benchmarks/` and the résumé.

- **FR-69:** the seed generator produces ≥5,000 synthetic users, deterministic under FR-50
- §19: **one k6 VU maps to one distinct synthetic user**, all profiles. Also keeps FR-20's 3-hold cap from throttling the spike artificially
- **A run with non-zero `RATE_LIMITED` is invalid, not annotated.** The report generator refuses to emit it. Rate limiting during a load test is a harness bug, not a property of the system under test
- FR-60 is tested by a dedicated integration test, never via a load profile
- FR-60 mechanism: two-bucket sliding-window-counter approximation. §10.5's `rate:{userId}` with a 1 s TTL is a *fixed* window, contradicting the requirement's own wording. Rejected alternative for DOC-4: a true ZSET sliding window — O(log N) and real memory at 5,000 users, on the hot path
- §11.2: `RATE_LIMITED` and `QUEUE_REQUIRED` are both 429; k6 thresholds and Grafana panels must split on error code, not status, or admission pressure and rate-limit rejection blur into one line on the demo dashboard

---

## D. Corrections and small decisions

| Item | Change |
|---|---|
| FR-10 | "(FR-24)" → **FR-28/FR-29**. FR-24 is the payment-after-expiry race. |
| NG-4 | "specified in FR-38" → **FR-44**. FR-38 is RAC capacity. |
| §11.1 | `fareePaise` → `farePaise` |
| Phase 1 | **`AC-1.7` does not exist** — AC-1.6 jumps to AC-1.8. Renumber, or insert a placeholder noting deliberate retirement. §0 tells the Reviewer agent to reject untraceable IDs. |
| FR-15 | Cache key gains `{pool}`. GENERAL and TATKAL availability differ by definition (FR-10), so for 2 s one pool's answer is served for the other's query. |
| `bookings` | Add **`hold_expires_at`**, written at hold time. FR-24's branch and B2's ordering cannot be evaluated after C2's `FLUSHALL` if expiry lives only in Redis — and C2 runs during P2, concurrent with live payments. |
| FR-42 | **Unsold TATKAL berths merge into GENERAL at chart time**, as step (a.5) before promotion. FR-11 covers only "before charting"; FR-42 never mentioned the pool. Without this, a charted train shows empty berths beside a non-empty waitlist. |
| FR-43 | **Cancelling a `HELD` booking is a release, not a cancellation** — returns berths, lands on `EXPIRED`, no refund path. Do *not* add a `HELD → CANCELLED` edge. |
| §6.4 | Redraw once to pick up B2's `PAYMENT_PENDING → FAILED_REFUNDED` edge and the FR-43 clarification. |

---

## E. Phase restructuring

**The finish line moves.** §20 says Phase 1 is "resume-complete"; §1's engineering claim requires *two* strategies; §22 says "once Phase 2 is complete". §20 is the outlier and the most dangerous line in the document — it authorises stopping at the point where TatkalRush becomes an ordinary booking system with a Lua script. Everything distinctive lives in the comparison. **Declare Phase 2 the finish line.**

**FR-28–FR-31 (Tatkal window plus `Clock` bean) move to Phase 1.** Roughly two days of work: FR-30 makes unlock a pure function of clock time, with no job. It is the feature the project is named after, and P1 is currently called "Tatkal spike" while running in a phase where the Tatkal window does not exist — a spike against an unlocked GENERAL pool.

**Phase 3 splits.** 3a = RAC/WL, promotion, chart prep, P5 — domain depth, self-contained, demoable. 3b = admission control plus React dashboard — the expensive demo surface. That gives a real stopping point between them instead of one all-or-nothing bucket.

**Phase 1 gains internal checkpoints:** (1a) domain + Strategy A + T-1…T-4 + T-7, no HTTP; (1b) full lifecycle over REST + PSP sim; (1c) invariants + k6 + dashboards + report.

**Sizing** — competent solo developer with strong AI assistance, full-time:

| Phase | Estimate |
|---|---|
| Phase 0 | 1–2 weeks |
| Phase 1 (+ Tatkal window) | 4–6 weeks |
| Phase 2 | 4–6 weeks |
| **Minimum defensible artifact** | **9–14 weeks** |
| Phase 3a | 2–3 weeks |
| Phase 3b | 3–4 weeks |

Evenings and weekends: multiply by roughly three. Phase 2 is the one that gets underestimated — transactional producers, rebalance handling, replay with command buffering, fencing across Kafka *and* Postgres (B7), and a response cache that survives owner death (B3) is not "a second implementation of an interface".

**Resolved 2026-09-04:** Phase 3 is in scope. Full-scope estimate is therefore **14–21 weeks full-time** (9–14 for Phase 0–2, plus 2–3 for 3a and 3–4 for 3b), roughly triple on evenings and weekends. If Phase 2 overruns its upper bound by more than half, cut **3b before 3a** — the dashboard is a demo asset; RAC/WL is domain substance and is what an interviewer will probe. See DD-018.

---

## F. Aggregate effect on §14

| ID | Status |
|---|---|
| INV-1 … INV-10 | unchanged, except **INV-9 rewritten** (B5) |
| **INV-11** | new — no refund with `reason='ALLOCATION_CONFLICT'` (B2) |
| **INV-12** | new — free counts match masks, post-run quiesced (B6) |

NFR-9 amended to cover allocation-constraint violations alongside invariant violations. §19 gains a run-validity gate: non-zero `RATE_LIMITED` voids the run.

---

## G. §23 open questions register

§23 states "Empty at v1.0 — all design decisions were resolved before drafting." That claim did not survive this session and should be removed; the register is a working instrument, not a scoreboard. Seed it with the one item still open:

| ID | Section | Question | Raised by | Status |
|---|---|---|---|---|
| OQ-1 | §20 | Calendar budget — determines whether Phase 3a/3b are in scope | grilling 2026-09-04 | **RESOLVED** 2026-09-04 — Phase 3 in scope (DD-018) |

---

## H. Three amendments worth attacking before they land

These were accepted without argument. Each has a real counter-case, and the SDD is stronger if the author breaks them first or convinces himself they hold.

1. **B2's build-failing constraint metric.** A single `EXCLUDE` violation anywhere in a 30-minute P4 soak reds the build. Is that too brittle? Counter-case: a legitimate benign trip during C2 recovery, if B2's ordering rule has a gap.
2. **B5's derived positions.** `ROW_NUMBER()` over an unbounded waitlist, per query — does the partial index actually keep this cheap at P5's cancellation rate, or does it just move the cost from writes to reads?
3. **B7's async checkpointing.** What is the recovery cost when the writer thread falls behind the consumer during a sustained spike — how stale can the checkpoint get, and does that breach NFR-8's warm 2 s number?
