# syntax=docker/dockerfile:1
#
# Generated with the Micronaut Gradle plugin (`./gradlew dockerfile`, which writes build/docker/main/Dockerfile) and made
# self-contained, as the plugin's docs suggest: the first stage runs `buildLayers` (the layered jar the generated
# Dockerfile copies from), the second is the generated runtime stage, on the same base image build.gradle.kts sets.
#
#   docker build -t live-communication-service .
#   docker run --rm -p 8080:8080 --env-file .env live-communication-service
#
# Configuration comes from the environment (see src/main/resources/application.yml):
#   JWT_SECRET                                         HS256 secret, 32+ characters
#   LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET   the LiveKit project
#   DATASOURCES_DEFAULT_URL / _USERNAME / _PASSWORD    PostgreSQL
#   REDIS_URI                                          Redis
#   MICRONAUT_ENVIRONMENTS                             optional, e.g. "quic"; never "dev" in a deployed image

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# The wrapper and build scripts first, so the Gradle distribution and dependencies stay cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain buildLayers

FROM eclipse-temurin:25-jre
WORKDIR /home/app
# Nothing in the service needs root: run as an unprivileged user. The ids are numeric because `COPY --link` cannot resolve a
# user name in --chown.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/libs /home/app/libs
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/app /home/app/
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/resources /home/app/resources
USER 10001:10001
# Size the heap from the container's memory limit rather than the JVM's small default share of it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/home/app/application.jar"]
