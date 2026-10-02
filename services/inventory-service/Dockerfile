# ---- build stage: compile the app with Maven (no local JDK/Maven needed) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# The OpenTelemetry Java agent: zero-code instrumentation for HTTP, JDBC, logging, JVM metrics...
ARG OTEL_AGENT_VERSION=2.10.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8081
# The agent is attached with -javaagent; all of its configuration comes from OTEL_* env vars (see docker-compose.yml)
ENTRYPOINT ["java", "-javaagent:/otel/opentelemetry-javaagent.jar", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
