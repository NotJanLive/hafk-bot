FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -q -DskipTests package

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/NotJanLive/hafk-bot"
RUN useradd --system --uid 10001 hafk
WORKDIR /app
COPY --from=build /src/target/hafk-bot.jar /app/hafk-bot.jar
USER hafk
ENV BOT_API_HOST=0.0.0.0 BOT_API_PORT=8081
EXPOSE 8081
ENTRYPOINT ["/bin/sh", "-c", "export BOT_API_PORT=\"${SERVER_PORT:-$BOT_API_PORT}\"; exec java -XX:MaxRAMPercentage=\"${MAX_RAM_PERCENT:-75}\" -jar /app/hafk-bot.jar"]
