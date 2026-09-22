# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Resolve dependencies first so that a source-only change does not re-download them.
COPY pom.xml .
COPY backend/pom.xml backend/
COPY cli/pom.xml cli/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY backend/src backend/src
COPY cli/src cli/src
RUN mvn -B package

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as an unprivileged user; the storage volume is the only writable path needed.
RUN groupadd --system --gid 1001 filevault \
    && useradd --system --uid 1001 --gid filevault --create-home filevault

WORKDIR /app
COPY --from=build /workspace/backend/target/filevault-backend-1.0.0.jar app.jar

RUN mkdir -p /data/storage && chown -R filevault:filevault /data /app
USER filevault

VOLUME ["/data"]
EXPOSE 8080

ENV APP_STORAGE_PATH=/data/storage \
    SPRING_DATASOURCE_URL="jdbc:sqlite:/data/filevault.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on" \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Uses the unauthenticated health probe, so it works with API-key auth enabled.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/files/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
