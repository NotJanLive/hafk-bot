package de.notjan.bot.api;

import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.Context;

public interface ApiController extends EndpointGroup {

    static <T> T readBody(Context ctx, Class<T> type) {
        try {
            return ctx.bodyAsClass(type);
        } catch (Exception e) {
            throw ApiException.badRequest("Ungültiger JSON-Body.");
        }
    }

    static long longParam(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " ist ungültig.");
        }
    }
}
