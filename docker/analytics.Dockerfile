# Build context: repository root
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY proto ./proto
COPY services/analytics-service ./services/analytics-service
COPY services/ingestion-service/build.gradle ./services/ingestion-service/build.gradle
RUN ./gradlew --no-daemon :services:analytics-service:bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home pulse
WORKDIR /app
COPY --from=build /src/services/analytics-service/build/libs/analytics-service-*.jar app.jar
RUN mkdir -p /data/kafka-streams && chown -R pulse /data
USER pulse
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    STREAMS_STATE_DIR=/data/kafka-streams
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
