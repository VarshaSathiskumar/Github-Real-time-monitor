# Build context: repository root
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY proto ./proto
COPY services/ingestion-service ./services/ingestion-service
COPY services/analytics-service/build.gradle ./services/analytics-service/build.gradle
RUN ./gradlew --no-daemon :services:ingestion-service:bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 pulse
WORKDIR /app
COPY --from=build /src/services/ingestion-service/build/libs/ingestion-service-*.jar app.jar
USER pulse
EXPOSE 8081 9090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fs http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
