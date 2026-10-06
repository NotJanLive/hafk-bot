package de.notjan.bot.api;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

public final class ApiAuth implements Handler {

    private static final String PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/v1/health");

    private final byte[] expectedToken;

    public ApiAuth(String token) {
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(Context ctx) {
        if (PUBLIC_PATHS.contains(ctx.path())) {
            return;
        }
        if (!isAuthorized(ctx.header("Authorization"))) {
            throw new ApiException(401, "unauthorized", "Missing or invalid API token");
        }
    }

    boolean isAuthorized(String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] provided = header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, provided);
    }
}
