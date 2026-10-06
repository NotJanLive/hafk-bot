package de.notjan.bot.api;

import de.notjan.bot.config.BotConfig.ApiConfig;
import de.notjan.bot.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static io.javalin.apibuilder.ApiBuilder.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private static final String TOKEN = "t".repeat(40);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ApiServer server;

    @BeforeAll
    static void start() {
        ApiController stub = () -> {
            get("/health", ctx -> ctx.json(Map.of("status", "ok")));
            get("/secret", ctx -> ctx.json(Map.of("value", 42)));
            get("/missing", ctx -> {
                throw ApiException.notFound("Nicht da.");
            });
            get("/crash", ctx -> {
                throw new IllegalStateException("boom");
            });
        };
        server = ApiServer.start(new ApiConfig("127.0.0.1", 0, TOKEN), Json.mapper(), List.of(stub));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void healthIsPublic() throws Exception {
        assertEquals(200, call("/api/v1/health", null).statusCode());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        HttpResponse<String> response = call("/api/v1/secret", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"unauthorized\""));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        assertEquals(401, call("/api/v1/secret", "x".repeat(40)).statusCode());
    }

    @Test
    void acceptsValidToken() throws Exception {
        HttpResponse<String> response = call("/api/v1/secret", TOKEN);

        assertEquals(200, response.statusCode());
        assertEquals("{\"value\":42}", response.body());
    }

    @Test
    void rendersApiExceptions() throws Exception {
        HttpResponse<String> response = call("/api/v1/missing", TOKEN);

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"message\":\"Nicht da.\""));
    }

    @Test
    void hidesInternalErrors() throws Exception {
        HttpResponse<String> response = call("/api/v1/crash", TOKEN);

        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("internal_error"));
        assertTrue(!response.body().contains("boom"));
    }

    private static HttpResponse<String> call(String path, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
