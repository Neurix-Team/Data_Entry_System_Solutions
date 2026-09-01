# --- Stage 1: build the JAR ---
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy build metadata and sources, then resolve only the dependencies the real build needs.
# dependency:go-offline also resolves optional plugin metadata and can hang for minutes on
# unavailable third-party repositories used by the PDF stack.
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package \
 && mv target/*.jar app.jar

# --- Stage 2: runtime ---
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Tesseract OCR runtime + Arabic + English language packs (needed by tess4j for image OCR)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      tesseract-ocr \
      tesseract-ocr-eng \
      tesseract-ocr-ara \
      libtesseract-dev \
 && rm -rf /var/lib/apt/lists/*

# Path where Tesseract keeps its trained language data on Ubuntu Jammy (Tesseract 4.1.x package)
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/4.00/tessdata/

# Non-root user for a bit of hardening
RUN groupadd -r app && useradd -r -g app app \
 && mkdir -p /app/data \
 && chown -R app:app /app

COPY --from=builder --chown=app:app /build/app.jar /app/app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xmx2g -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
