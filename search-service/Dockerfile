# ─── Stage 1: Build ──────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# ↓ prefix with search-service/ because build context is project root
COPY search-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -q

# ↓ same fix here
COPY search-service/src ./src
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime ──────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow
USER tradeflow

COPY --from=builder /app/target/*.jar app.jar

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8089

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8089/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]