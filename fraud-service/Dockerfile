####################################
# Stage 1 — Build
####################################
# We use the Maven image to avoid the missing 'mvnw' wrapper issue
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM from the fraud sub-folder
COPY fraud-service/pom.xml ./pom.xml

# 2. Pre-download dependencies (Heavy lifting)
RUN mvn dependency:go-offline -B

# 3. Copy source from the fraud sub-folder and build the JAR
COPY fraud-service/src ./src
RUN mvn clean package -DskipTests -B

####################################
# Stage 2 — Runtime
####################################
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow

# Copy the generated JAR from the builder stage
COPY --from=builder --chown=tradeflow:tradeflow /app/target/*.jar app.jar

USER tradeflow
EXPOSE 8085

# Spring Boot JVM tuning
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]