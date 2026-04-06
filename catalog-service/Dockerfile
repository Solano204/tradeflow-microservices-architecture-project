####################################
# Stage 1 — Build
####################################
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM specifically from the catalog folder
COPY catalog-service/pom.xml ./pom.xml

# 2. Download dependencies (Uses the Maven from the image, no mvnw needed)
RUN mvn dependency:go-offline -B

# 3. Copy source from catalog folder and build
COPY catalog-service/src ./src
RUN mvn clean package -DskipTests -B

####################################
# Stage 2 — Runtime
####################################
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Security: non-root user
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow

# Standard Spring Boot JAR location
COPY --from=builder --chown=tradeflow:tradeflow /app/target/*.jar app.jar

USER tradeflow
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8082/actuator/health || exit 1

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]