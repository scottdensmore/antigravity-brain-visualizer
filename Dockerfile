# syntax=docker/dockerfile:1
#
# Two-stage build for the Agent Brain Visualizer: compile the fat jar under a full JDK, then run it
# on a slim JRE. Self-contained — you don't need Java or Gradle on the host. Used by the `app`
# service in docker-compose.yml (`docker compose --profile full up -d --build`).

# --- build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# The cache mount keeps Gradle's downloaded dependencies across builds, so a code
# change rebuilds the jar without re-fetching the world.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean shadowJar -x test \
    && cp build/libs/*-all.jar /app.jar

# --- runtime stage ---------------------------------------------------------
# The base tags float on their major version deliberately: rebuilding picks up JRE security patches.
# Pin to a digest if you need bit-for-bit reproducible images.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app.jar app.jar

# Run as an unprivileged user, not root. The app only reads the jar and talks to Postgres — it needs
# no write access to the image.
RUN useradd --system --uid 10001 app
USER app

EXPOSE 8080

# Configuration comes from the environment (compose sets DATABASE_URL, credentials, and the optional
# AI keys), so ignore any stray .env that lands in the working directory.
ENTRYPOINT ["java", "-Ddotenv.enabled=false", "-jar", "app.jar"]
