FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 pyeonit \
    && useradd --uid 10001 --gid pyeonit --no-create-home pyeonit
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/*-SNAPSHOT.jar /app/backend.jar
ENV SERVER_PORT=8081
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --output /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
