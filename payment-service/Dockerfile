# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM from the payment-service folder
COPY payment-service/pom.xml ./pom.xml

# 2. Cache dependencies (Maven image includes Maven, no mvnw needed)
RUN mvn dependency:go-offline -B -q

# 3. Copy source and build Quarkus fast-jar
COPY payment-service/src ./src
RUN mvn clean package -DskipTests -B -q

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /work

# Non-root user for security
RUN addgroup -S tradeflow && adduser -S -G tradeflow tradeflow

# 4. Copy the entire Quarkus fast-jar layout from the builder
# This includes quarkus-run.jar, lib/, app/, and quarkus/
COPY --from=builder --chown=tradeflow:tradeflow /app/target/quarkus-app /work/quarkus-app

USER tradeflow

# Quarkus profile for production
ENV QUARKUS_PROFILE=prod

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

# Payment Service port: 8085
EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8085/q/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /work/quarkus-app/quarkus-run.jar"]