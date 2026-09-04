# TatkalRush application image.
#
# Base images are pinned BY DIGEST, not by tag (SDD 8.4, DD-003). A tag is a
# moving pointer; a digest names one exact image forever. This is not pedantry
# here: adapters/web is compiled with --enable-preview, and preview classfiles
# refuse to load on a different JDK major. A floating base image would silently
# swap the JVM underneath a committed benchmark.
#
# Refresh digests with ops/docker/refresh-digests.sh, never by hand.

# ---------------------------------------------------------------------------
# Stage 1 - dependency cache.
#
# Only the POMs are copied here, so this layer is invalidated by a dependency
# change and NOT by a source change. Without it, every rebuild re-resolves the
# whole tree and NFR-10's 120 s cold start is dominated by Maven downloads
# rather than by anything the system actually does (SDD 8.2).
# ---------------------------------------------------------------------------
FROM maven@sha256:a994afe5615a851896e0a8ad01071bc68ccf475c8164a60bef4d1d1a9bb718a0 AS deps

WORKDIR /build

COPY pom.xml ./
COPY domain/pom.xml                    domain/
COPY application/pom.xml               application/
COPY adapters/pom.xml                  adapters/
COPY adapters/persistence/pom.xml      adapters/persistence/
COPY adapters/allocator-redis/pom.xml  adapters/allocator-redis/
COPY adapters/allocator-swp/pom.xml    adapters/allocator-swp/
COPY adapters/messaging/pom.xml        adapters/messaging/
COPY adapters/payment-sim/pom.xml      adapters/payment-sim/
COPY adapters/web/pom.xml              adapters/web/
COPY admission/pom.xml                 admission/
COPY ops/pom.xml                       ops/
COPY ops/invariant-checker/pom.xml     ops/invariant-checker/
COPY ops/seed/pom.xml                  ops/seed/
COPY app/pom.xml                       app/
COPY archtest/pom.xml                  archtest/
COPY differential/pom.xml              differential/

RUN mvn -B -q dependency:go-offline -DexcludeArtifactIds=domain,application,persistence,allocator-redis,allocator-swp,messaging,payment-sim,web,admission,invariant-checker

# ---------------------------------------------------------------------------
# Stage 2 - build.
#
# -pl app -am builds the app module and everything it depends on, skipping
# archtest/ and differential/, which are verification modules with no runtime
# artifact. Tests are skipped here deliberately: they run in CI and in
# `mvn verify`, and running them inside the image build would make NFR-10
# measure the test suite instead of the stack.
# ---------------------------------------------------------------------------
FROM deps AS build

COPY . .
RUN mvn -B -q clean package -DskipTests -pl app -am

# ---------------------------------------------------------------------------
# Stage 3 - runtime.
# ---------------------------------------------------------------------------
FROM eclipse-temurin@sha256:589a2ad66f8ee9a7a610a41c24260201b4b223102929a35a8c558fadb5b9fabb AS runtime

# curl is needed by the compose healthcheck. AC-0.1 requires a real readiness
# probe rather than a fixed sleep, and the JRE image ships without one.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --create-home tatkal
USER tatkal
WORKDIR /opt/tatkal

COPY --from=build --chown=tatkal:tatkal /build/app/target/app-*.jar app.jar

# Heap is fixed rather than percentage-based so the number in SDD 8.3's table is
# the number the JVM actually uses, and NFR-11 stays auditable.
ENV JAVA_OPTS="-Xms320m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"

EXPOSE 8080

# --enable-preview: the THIRD of the three places it must appear (SDD 8.5).
# The other two are the compiler args and the surefire argLine in
# adapters/web/pom.xml. Omitting any one of them fails as an opaque
# UnsupportedClassVersionError about class file version 69.65535.
ENTRYPOINT ["sh", "-c", "exec java --enable-preview $JAVA_OPTS -jar app.jar"]
