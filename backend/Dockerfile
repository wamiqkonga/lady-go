# syntax=docker/dockerfile:1
# The jar is the same on every CPU, so the build stage always runs natively (fast) even when the final image
# is built for a different architecture (arm64 production host) with --platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build /workspace/target/ladygo-backend-*.jar app.jar
USER app
EXPOSE 8080
# Commit of the code in this image; shown at /actuator/info (set by the deploy pipeline).
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
