# syntax=docker/dockerfile:1
# Root-level copy of backend/Dockerfile so Render works with default settings
# (Root Directory empty, Dockerfile Path ./Dockerfile). Keep both files in sync.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app -u 10001 app \
    && mkdir -p /app/uploads && chown -R app:app /app
COPY --from=build /app/target/borcdefteri-backend-1.0.0.jar /app/app.jar
USER app
ENV PORT=8080 \
    UPLOAD_DIR=/app/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- "http://localhost:${PORT}/api/health" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
