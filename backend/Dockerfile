# syntax=docker/dockerfile:1
# RecruitPro backend: Maven build on Temurin 21, runtime on the Temurin 21 JRE.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package \
    && cp target/recruitment-platform-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system recruitpro \
    && useradd --system --gid recruitpro --home-dir /app --shell /usr/sbin/nologin recruitpro \
    && mkdir -p /app /data/uploads \
    && chown -R recruitpro:recruitpro /app /data
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER recruitpro
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75" \
    FILE_STORAGE_PATH=/data/uploads \
    PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
    CMD curl -fsS http://localhost:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
