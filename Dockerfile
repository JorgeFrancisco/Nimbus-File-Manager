# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
# Builds the executable Spring Boot jar. Kept separate from the runtime stage so
# the final image doesn't carry Maven, the JDK, or the source tree.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Copy only the POM first so this dependency download is cached by Docker and
# skipped on rebuilds that don't touch pom.xml.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests clean package

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

# ffmpeg provides both ffmpeg and ffprobe - the same tools the Windows setup downloads
# manually (see README, "External Tools"), here from Debian's package repos instead.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends ffmpeg \
	&& rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /usr/sbin/nologin nimbus-file-manager

WORKDIR /app
COPY --from=build /build/target/nimbus-file-manager-*.jar app.jar

# /workspace holds the app's own data (database migration lock files, logs, exports,
# temp, backup - see nimbus-file-manager.workspace-folders); /library is where you mount the
# actual media folder(s) you want the app to inventory/organize (see docker-compose.yml).
RUN mkdir -p /workspace /library && chown -R nimbus-file-manager:nimbus-file-manager /app /workspace /library

USER nimbus-file-manager

# No tool path is set: apt put ffmpeg and ffprobe on PATH above, which is exactly
# what the application falls back to when it has no downloaded copy of its own.
ENV NIMBUS_FILE_MANAGER_WORKSPACE=/workspace

EXPOSE 8088
VOLUME ["/workspace"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]