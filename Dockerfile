# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-22 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies in a separate layer for caching
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:22-jre-alpine
WORKDIR /app

# Install tar and borgbackup for backup engines
RUN apk add --no-cache tar borgbackup openssh-client

# Non-root user for safety
RUN addgroup -S docksnap && adduser -S docksnap -G docksnap

COPY --from=build /app/target/DockSnap-1.0-SNAPSHOT.jar app.jar

# Default mount points (override with env vars or volume mounts)
RUN mkdir -p /backups /config && chown docksnap:docksnap /backups /config

USER docksnap

ENV PORT=8080
ENV BACKUP_DIR=/backups
ENV JOBS_FILE=/config/jobs.json

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]