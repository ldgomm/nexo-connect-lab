# syntax=docker/dockerfile:1

FROM eclipse-temurin:21.0.11_10-jdk-ubi9-minimal AS build

WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY src ./src
RUN ./gradlew --no-daemon clean installDist --console=plain

FROM eclipse-temurin:21.0.11_10-jre-ubi9-minimal AS runtime

WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/build/install/connect-lab/ ./

USER 10001:10001
EXPOSE 8282
ENTRYPOINT ["/app/bin/connect-lab"]
