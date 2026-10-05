# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 hafk
WORKDIR /app
COPY --from=build /src/target/hafk-bot.jar app.jar
USER hafk
# The API must be reachable from the dashboard container, but is not published to the host.
ENV API_HOST=0.0.0.0
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
