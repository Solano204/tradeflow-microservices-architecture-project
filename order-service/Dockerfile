# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM specifically from the order-service folder
COPY order-service/pom.xml ./pom.xml

# 2. Cache dependencies
RUN mvn dependency:go-offline -B -q

# 3. Copy source and build
COPY order-service/src ./src
RUN mvn clean package -DskipTests -B -q

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow
USER tradeflow

# Grab the built JAR from the builder stage
COPY --from=builder /app/target/*.jar app.jar

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENV SPRING_PROFILES_ACTIVE=prod

# Order Service port: 8084
EXPOSE 8084

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8084/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]