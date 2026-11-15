# syntax=docker/dockerfile:1
#
# Generated with the Micronaut Gradle plugin (`./gradlew dockerfile`, which writes build/docker/main/Dockerfile) and made
# self-contained, as the plugin's docs suggest: the first stage runs `buildLayers` (the layered jar the generated
# Dockerfile copies from), the second is the generated runtime stage, on the same base image build.gradle.kts sets.
#
#   docker build -t live-communication-service .
#   docker run --rm -p 8080:8080 --env-file .env live-communication-service
#
# The image runs with the "cloud" profile (src/main/resources/application-cloud.yml: hosted PostgreSQL on Neon and Redis on
# Upstash, hardcoded), so it only needs JWT_SECRET and the LiveKit variables below. Override MICRONAUT_ENVIRONMENTS to point it
# elsewhere, and set the variables below instead.
#
# On Render (render.yaml) the same image works unchanged: the service binds the port in PORT (application-cloud.yml), 10000 there, 8080 elsewhere.
#
# Configuration comes from the environment (see src/main/resources/application.yml):
#   JWT_SECRET                                         HS256 secret, 32+ characters
#   LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET   the LiveKit project
#   DATASOURCES_DEFAULT_URL / _USERNAME / _PASSWORD    PostgreSQL
#   REDIS_URI                                          Redis
#   MICRONAUT_ENVIRONMENTS                             defaults to "cloud"; e.g. "cloud,quic" adds HTTP/3

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# The wrapper and build scripts first, so the Gradle distribution and dependencies stay cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src
# Maven Central sometimes drops a connection mid-build. Gradle gets longer timeouts and more download attempts, the whole step is
# retried, and the cache mount keeps what was already downloaded, so every attempt (and a re-run of `docker build`) resumes.
RUN --mount=type=cache,target=/root/.gradle \
    for attempt in 1 2 3; do \
        ./gradlew --no-daemon --console=plain \
            -Dorg.gradle.internal.http.connectionTimeout=60000 \
            -Dorg.gradle.internal.http.socketTimeout=120000 \
            -Dorg.gradle.internal.repository.max.tentatives=5 \
            buildLayers && break; \
        [ "$attempt" = 3 ] && exit 1; \
        echo "Gradle failed (attempt $attempt of 3), retrying"; sleep 10; \
    done

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
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    MICRONAUT_ENVIRONMENTS=cloud
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/home/app/application.jar"]
