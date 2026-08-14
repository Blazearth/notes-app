# ── Stage 1: build the fat JAR ───────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

# Cache Maven deps before copying source
COPY extraction/mvnw extraction/pom.xml ./
COPY extraction/.mvn .mvn
RUN ./mvnw dependency:go-offline -q

COPY extraction/src src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-noble

# External binaries this service invokes directly (docs/extraction-architecture.md
# Part D: "the extraction service owns everything that touches the media
# bytes"). Same three as the monolith's own Dockerfile, plus the same F7 gap
# this doc's Phase 6 still has to close — pinned at build time rather than
# updated on start.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
        tesseract-ocr-eng \
        python3 \
        python3-pip \
        pipx \
    && pipx install "yt-dlp>=2026.7.4" \
    && ln -s /root/.local/bin/yt-dlp /usr/local/bin/yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

# No public ingress is the deployment plan (Part D Security / Phase 6) — this
# EXPOSE is for the private network the backend reaches it over, not a
# public port mapping.
EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-jar", "app.jar"]
