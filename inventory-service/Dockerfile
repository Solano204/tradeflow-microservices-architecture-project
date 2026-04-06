# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM from the sub-folder
COPY inventory-service/pom.xml ./pom.xml

# 2. Pre-download dependencies (using Maven from the image)
RUN mvn dependency:go-offline -B -q

# 3. Copy source from the sub-folder and build the JAR
COPY inventory-service/src ./src
RUN mvn clean package -DskipTests -B -q

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow
USER tradeflow

COPY --from=builder /app/target/*.jar app.jar

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENV SPRING_PROFILES_ACTIVE=prod

# Fixed to match your server.port=8083
EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]