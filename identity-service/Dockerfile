# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy POM from the sub-folder
COPY identity-service/pom.xml ./pom.xml

# 2. Pre-download dependencies (Uses the image's Maven)
RUN mvn dependency:go-offline -B

# 3. Copy source and build
COPY identity-service/src ./src
RUN mvn clean package -DskipTests -B

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow
USER tradeflow

# Copy the generated JAR
COPY --from=builder --chown=tradeflow:tradeflow /app/target/*.jar app.jar

# JVM flags optimized for containers + Virtual Threads
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:+OptimizeStringConcat \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8"

# Activate prod profile
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]