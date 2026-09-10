# Runbook — bringing a load-testable system up

G-5 claims reproducibility. That claim is only as good as the exact commands
below, so these are the ones actually run on the build machine, in order, with
the two things that silently go wrong called out where they bite.

Everything here assumes `E:\TatkalRush` and Docker Desktop running.

---

## 1. Build the image *before* starting the stack

```bash
docker compose build app-1
```

**`docker compose up` will happily reuse a stale image.** It does not notice that
the source tree moved on. On 2026-09-10 the running stack was serving an image
built on 2026-09-04 — Phase 0's health-check-only app — and every call to
`/api/v1/trains/search` returned a plain 404 with no hint that the container was
six days behind the repository. Build first, or `--build` on the way up.

## 2. Start the stack

```bash
docker compose up -d --wait --wait-timeout 300
```

Healthy in ~35 s on the reference machine (NFR-10 budget: 120 s).

## 3. Seed Postgres

```bash
java -cp "ops/seed/target/classes;domain/target/classes;%USERPROFILE%\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar" io.tatkalrush.ops.seed.SeedMain "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal
```

~21 s for 3,600 pools and 291,120 bookable berth-instances (AC-0.2 budget: 60 s).

**Not `mvn exec:java`.** `SeedMain`'s javadoc suggests
`mvn -pl ops/seed -am compile exec:java`, and it does not work: with `-am` the
goal binds to the root aggregator rather than to `ops/seed`, and without `-am` a
single-module build cannot resolve sibling modules that were never `mvn install`ed.
The `java -cp` form above needs no plugin and no installed artifacts.

## 4. Provision Redis — **this step is not optional**

```bash
java -cp "<same classpath plus allocator-redis, application, lettuce, netty, reactor>" io.tatkalrush.ops.warmup.PoolWarmupMain "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal localhost 6379
```

~8.4 s for 3,600 pools / 291,120 berth slots.

**Seeding Postgres does not provision Redis.** Postgres knows which berths
exist; Redis is what the allocator actually reads. Skip this and every hold
fails with `pool not provisioned` and every search reports `availableBerths:
null` for every class — which looks like a bug in the search path and is not.

Run it again after chaos scenario C2 (`redis-cli FLUSHALL`). It is idempotent:
`init-pool.lua` writes whole pool state rather than mutating it.

## 5. Open the Tatkal window (only for §19's profiles)

```bash
TATKAL_CLOCK_OFFSET=P40D docker compose up -d app-1 app-2
```

The seed's `BASE_DATE` is fixed at 2026-10-01 so two runs stay comparable
(FR-50), which means every TATKAL window in the dataset is shut for anyone
running before late September 2026. AC-1.11 requires P1 to run against an **open**
one, so the clock moves instead of the data — shifting `BASE_DATE` would
invalidate every committed benchmark for comparison purposes.

Pick the offset so that "now" lands **on or after** the journey date minus one
day: sleeper TATKAL opens 11:00 IST on D-1, AC at 10:00 IST. `P40D` from
2026-09-10 puts the system at 2026-10-20, which opens journeys from 2026-10-21.

The app logs a `WARN` at startup whenever the offset is non-zero. If you do not
see it, the offset did not reach the container.

---

## Smoke test

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token -H 'Content-Type: application/json' -d '{"userId":1}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s "http://localhost:8080/api/v1/trains/search?from=AGC&to=RTM&date=2026-10-21&class=SL" -H "Authorization: Bearer $TOKEN"
```

A healthy system answers with `approximate: true`, non-null `availableBerths`,
and — on a second call inside 2 seconds — `cache: {hits: N, misses: 0}` with
`stale: true`. Measured on the reference machine: 1.24 s cold, 0.018 s warm.

Two seconds is the whole TTL (FR-15), so two `curl` calls typed by hand will
usually both miss. That is the cache expiring correctly, not the cache failing.

---

## Tearing down

```bash
docker compose down -v
```

`-v` drops the Postgres volume, so the next run starts from an empty schema and
step 3 is required again. Without it the seed will fail on duplicate keys.
