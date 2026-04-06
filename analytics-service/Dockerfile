# ==================================================
# TradeFlow Analytics Service - Multi-stage Build
# ==================================================

FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom.xml and download dependencies (cache layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==================================================
# Runtime Image
# ==================================================

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create user for security
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow

# Copy JAR from builder
COPY --from=builder /build/target/*.jar app.jar

# Create directories for RocksDB state
RUN mkdir -p /app/data/kafka-streams && \
    chown -R tradeflow:tradeflow /app

USER tradeflow

EXPOSE 8090

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", \
  "app.jar"]

# Run with prod profile
CMD ["--spring.profiles.active=prod"]