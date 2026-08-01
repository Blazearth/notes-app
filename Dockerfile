# ── Stage 1: build the fat JAR ───────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

# Cache Maven deps before copying source
COPY api/mvnw api/pom.xml ./
COPY api/.mvn .mvn
RUN ./mvnw dependency:go-offline -q

COPY api/src src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble

# External binaries the pipeline requires at runtime.
# A plain JRE image has none of these — the app fails to start jobs silently
# if they are missing (yt-dlp, ffmpeg) or returns zero-text frames (tesseract).
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
        tesseract-ocr-eng \
        python3 \
        python3-pip \
        pipx \
    && pipx install yt-dlp \
    && ln -s /root/.local/bin/yt-dlp /usr/local/bin/yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

# Render injects PORT; default to 8080 so local docker run still works.
EXPOSE 8080

# Graceful shutdown: Spring listens for SIGTERM and drains in-flight requests.
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "app.jar"]
