package de.notjan.bot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.config.BotConfig.ApiConfig;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static io.javalin.apibuilder.ApiBuilder.path;

/**
 * Internal REST API consumed by the dashboard backend. Binds to {@code BOT_API_HOST} (default localhost).
 */
public final class ApiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);

    private final Javalin app;

    private ApiServer(Javalin app) {
        this.app = app;
    }

    public static ApiServer start(ApiConfig config, ObjectMapper json, List<ApiController> controllers) {
        Javalin app = Javalin.create(javalin -> {
            javalin.startup.showJavalinBanner = false;
            javalin.jsonMapper(new JavalinJackson(json, false));

            javalin.routes.before(new ApiAuth(config.token()));
            javalin.routes.apiBuilder(() -> path("/api/v1", () -> controllers.forEach(ApiController::addEndpoints)));

            javalin.routes.exception(ApiException.class, (e, ctx) -> ctx.status(e.status()).json(e.body()));
            javalin.routes.exception(HttpResponseException.class, (e, ctx) -> {
                var error = new ApiException(e.getStatus(), "http_" + e.getStatus(), e.getMessage());
                ctx.status(e.getStatus()).json(error.body());
            });
            javalin.routes.exception(Exception.class, (e, ctx) -> {
                LOG.error("Unhandled API error on {} {}", ctx.method(), ctx.path(), e);
                var error = new ApiException(500, "internal_error", "Interner Fehler im Bot.");
                ctx.status(500).json(error.body());
            });
        }).start(config.host(), config.port());

        LOG.info("API listening on {}:{}", config.host(), config.port());
        return new ApiServer(app);
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }
}
