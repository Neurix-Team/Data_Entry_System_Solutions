FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package \
 && mv target/*.jar app.jar

# Runtime: Ubuntu 26.04 LTS (resolute). tess4j 5.20 binds directly to Leptonica >= 1.85 —
# it resolves symbols such as pixBackgroundNormTo1MinMax (1.83) and readResolutionMemJp2k
# (1.85) when the first image is processed. Ubuntu 22.04/24.04 ship Leptonica 1.82 and
# Debian 13 ships 1.84, so on those bases every OCR call fails with
# "OCR engine is not available (UnsatisfiedLinkError: ... undefined symbol ...)".
# resolute ships Tesseract 5.5.0 + Leptonica 1.86.0, and Temurin publishes a JRE image for it.
FROM eclipse-temurin:17-jre-resolute

WORKDIR /app

# fontconfig + DejaVu: Java2D text rendering (PDF page rendering and the boot-time OCR
# smoke test) needs at least one font; a headless JRE image ships none.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      curl \
      fontconfig \
      fonts-dejavu-core \
      tesseract-ocr \
      tesseract-ocr-eng \
      tesseract-ocr-ara \
      libtesseract-dev \
 && rm -rf /var/lib/apt/lists/*

# Tesseract 5 keeps its language files here (4.x used .../4.00/tessdata). The app
# auto-detects either layout; the variable is a hint for tools run by hand in the container.
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata/

RUN groupadd -r app && useradd -r -g app app \
 && mkdir -p /app/data \
 && chown -R app:app /app

COPY --from=builder --chown=app:app /build/app.jar /app/app.jar

USER app

EXPOSE 8080

EXPOSE 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent http://localhost:9090/actuator/health >/dev/null || exit 1

# jna.library.path: let tess4j find libtesseract even when the ld cache is stale or the
# host mounts an unusual library layout; both amd64 and arm64 locations are listed.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -Djna.library.path=/usr/lib/x86_64-linux-gnu:/usr/lib/aarch64-linux-gnu:/usr/lib"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
