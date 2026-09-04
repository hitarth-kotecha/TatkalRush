# 000 — Hardware calibration (AC-0.7)

**Date:** 2026-09-04 · **Phase:** 0 · **Gate:** AC-0.7 · **Decision:** [DD-019](../design-decisions.md#dd-019)
**Git SHA:** *(uncommitted working tree — repository initialised, first commit pending)*

This is the first entry in `docs/benchmarks/`, and it is not a performance
result. It is the measurement that tells every later phase what numbers are
achievable on this machine, so that NFR-1 and NFR-2 are set from evidence rather
than from the 16 GB laptop v1.2 assumed.

---

## NFR-12 metadata

Required on every reported number, without exception.

| | |
|---|---|
| **CPU** | Intel Core i5-10300H @ 2.50 GHz — **4 physical cores, 8 logical** |
| **Host RAM** | 7.91 GB total |
| **OS** | Windows 10 Home Single Language 19045 |
| **Docker** | 29.5.3, WSL2 backend — 8 CPUs, **3.78 GiB** allocated to the VM |
| **JDK (container)** | Temurin **25.0.4+7** LTS, `--enable-preview` |
| **Spring Boot** | 4.0.8 |
| **Load generator** | k6 **v2.2.0**, **co-located on the same machine** (NFR-13) |
| **Topology** | nginx round-robin → 2 app replicas (`app-1`, `app-2`) |
| **Container limits** | kafka 1024m · postgres 512m · redis 512m · app-1/app-2 512m each (`-Xmx320m`) · psp-sim 256m (`-Xmx160m`) · prometheus 384m · grafana 256m · nginx/toxiproxy 32m |
| **Step duration** | 20 s per rate, after a 30 s discarded warmup |

**k6 shares this laptop with the system under test.** Headroom on dedicated
infrastructure would be higher. No estimate of how much higher is offered, per
NFR-13.

---

## Part 1 — Memory (NFR-11)

Sampled every 30 s over a 5-minute idle window.

| Container | Peak |
|---|---:|
| nginx | 7.1 MiB |
| toxiproxy | 7.8 MiB |
| redis | 10.9 MiB |
| prometheus | 46.3 MiB |
| grafana | 78.0 MiB |
| postgres | 84.1 MiB |
| psp-sim | 195.2 MiB |
| app-2 | 310.4 MiB |
| app-1 | 319.1 MiB |
| kafka | 506.4 MiB |
| **Total (sum of peaks)** | **1,565.3 MiB** |
| NFR-11 budget | 4,608 MiB |

**PASS — 34 % of budget used.** Sum of per-container peaks rather than peak of
the sum: peaks occur at different moments, so this over-counts slightly, which is
the conservative direction for a ceiling.

Also recorded: cold start `docker compose up --wait` to all-healthy in **32 s**
against NFR-10's 120 s (AC-0.1).

---

## Part 2 — HTTP ceiling

Neither figure below is NFR-1 or NFR-2. They are the **upper bound** those can
never exceed, measured with no domain work in the path. AC-1.13 sets the real
values against `search` and `hold` in Phase 1c, and they will be a fraction of
these.

### Pure HTTP path — `/actuator/health/liveness`

App state only, no I/O.

| Requested rps | Achieved | p50 ms | p95 ms | p99 ms | |
|---:|---:|---:|---:|---:|---|
| 100 | 100.0 | 1.61 | 2.65 | 3.64 | ok |
| 250 | 250.0 | 1.51 | 2.33 | 3.19 | ok |
| 500 | 500.0 | 1.04 | 2.31 | 39.15 | ok |
| 750 | 749.9 | 1.00 | 2.00 | 13.16 | ok |
| 1000 | 999.9 | 1.00 | 2.13 | **57.69** | p99 over 50 ms |

**Ceiling: 750 rps within a 50 ms p99. Breached at 1000.**

### With one backend round trip — `/actuator/health`

Also checks Postgres, Redis, disk and SSL.

| Requested rps | Achieved | p50 ms | p95 ms | p99 ms | |
|---:|---:|---:|---:|---:|---|
| 100 | 100.0 | 2.16 | 3.31 | 4.30 | ok |
| 250 | 250.0 | 2.08 | 3.00 | 5.00 | ok |
| 500 | 500.0 | 1.64 | 3.00 | 5.66 | ok |
| 750 | 750.0 | 2.00 | 5.00 | **57.98** | p99 over 50 ms |

**Ceiling: 500 rps within a 50 ms p99. Breached at 750.**

The gap between the two — **750 vs 500 rps, about a third** — is the cost of
touching a backend at all, before any allocation logic exists. Worth remembering
in Phase 1 before latency is attributed to the allocator.

---

## Threats to validity

Stated plainly, because a calibration that oversells itself corrupts everything
built on it.

**p50 and p95 are stable; p99 is not.** Liveness p99 reads 39.15 ms at 500 rps
and 13.16 ms at 750 — non-monotonic, and by a wide margin. At 20 s per step a
500 rps run is 10,000 requests, so p99 is the 100 slowest, and a single GC pause
moves it substantially. **The knee is real; its exact position is ±1 step.**
AC-1.13 should use longer steps.

**k6 is co-located** and competes for the same 8 logical cores. Some of the
measured ceiling is the load generator.

**Health-check endpoints are not application endpoints.** `search` and `hold`
will do materially more work. These numbers bound them from above; they do not
predict them.

**The first attempt at this measurement was wrong twice**, and both errors are
worth recording because both produced *plausible-looking numbers*:

1. **No warmup.** Reported 6.5 rps at 4.3 s average against a target of 20 —
   which reads as catastrophic hardware. It was JIT compilation; the same
   endpoint sustained 50 rps at 6.7 ms p95 thirty seconds later.
2. **Ramping past the knee to 4000 rps**, and computing a ceiling from steps
   where k6 dropped tens of thousands of iterations. Those steps measured the
   load generator, not the system. The overload also left nginx unhealthy and
   `/actuator/health` taking **139 seconds** sequentially for minutes afterwards,
   contaminating the second endpoint's entire ramp.

The harness now enforces a warmup, voids any step that drops iterations rather
than annotating it (SDD §19.5's rule one layer down), and stops at the first
breach.

---

## Consequence: OQ-2, resolved

AC-0.7 as originally written said *"if the HTTP ceiling is below roughly
1,000 req/s, escalate to OQ-2"*. Measured 750, so the gate fired.

That threshold had no derivation — it was invented before any measurement, on
the reasoning that real endpoints are a fraction of a health check. It has been
**replaced by a derived floor of 400 rps** and OQ-2 is closed
([DD-029](../design-decisions.md#dd-029)).

The derivation: P1 must exhaust a single train's inventory (~500 berths in a
class) inside a 30-second spike, needing ~17 successful holds/s; a spike whose
purpose is contention needs roughly an order of magnitude more attempts than
successes. Below 400 rps on a no-I/O endpoint, that is unreachable once real
endpoint work is subtracted.

**The question OQ-2 was a proxy for is still open.** Whether P3 generates enough
contention for §9.4 to discriminate the two allocators cannot be answered by a
health-check rate. **AC-1.13 carries it**, and reopens the venue decision if
measured `hold` throughput is below ~40 rps, or if P3's p99 distributions for the
two strategies overlap within noise.

---

## Reproduce

```bash
docker compose up -d --wait
./ops/docker/measure-memory.sh 300 30
./loadtest/calibration/run-calibration.sh
```
