####################################
# Stage 1 — Build
####################################
# Use a full Maven image so we don't need the 'mvnw' wrapper
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# 1. Copy the POM from the module folder
COPY auth-service/pom.xml ./pom.xml

# 2. Download dependencies (This takes a minute)
RUN mvn dependency:go-offline -B

# 3. Copy the source code and build
COPY auth-service/src ./src
RUN mvn package -DskipTests -B

####################################
# Stage 2 — Runtime
####################################
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S tradeflow && adduser -S tradeflow -G tradeflow

# Quarkus specific structure
COPY --from=build --chown=tradeflow:tradeflow /app/target/quarkus-app/lib/ lib/
COPY --from=build --chown=tradeflow:tradeflow /app/target/quarkus-app/*.jar ./
COPY --from=build --chown=tradeflow:tradeflow /app/target/quarkus-app/app/ app/
COPY --from=build --chown=tradeflow:tradeflow /app/target/quarkus-app/quarkus/ quarkus/

USER tradeflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]