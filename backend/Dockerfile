FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package \
 && mv target/*.jar app.jar

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      tesseract-ocr \
      tesseract-ocr-eng \
      tesseract-ocr-ara \
      libtesseract-dev \
 && rm -rf /var/lib/apt/lists/*

ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/4.00/tessdata/

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

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
