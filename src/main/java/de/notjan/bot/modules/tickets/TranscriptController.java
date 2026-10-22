package de.notjan.bot.modules.tickets;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.ApiException;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.modules.tickets.TranscriptService.AssetFile;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import static io.javalin.apibuilder.ApiBuilder.get;

final class TranscriptController implements ApiController {

    private final TranscriptService service;

    TranscriptController(TranscriptService service) {
        this.service = service;
    }

    @Override
    public void addEndpoints() {
        get("/transcripts/{uuid}", this::transcript);
        get("/transcripts/{uuid}/assets/{assetId}", this::asset);
    }

    private void transcript(Context ctx) {
        long userId = GuildGuard.actingUser(ctx);
        ctx.json(service.transcript(ctx.pathParam("uuid"), userId));
    }

    private void asset(Context ctx) {
        long userId = GuildGuard.actingUser(ctx);
        long assetId;
        try {
            assetId = Long.parseLong(ctx.pathParam("assetId"));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("assetId ist ungültig.");
        }
        AssetFile file = service.asset(ctx.pathParam("uuid"), assetId, userId);
        try {
            ctx.contentType(file.contentType());
            ctx.header("Content-Length", String.valueOf(Files.size(file.path())));
            ctx.header("X-File-Name", file.fileName());
            ctx.result(Files.newInputStream(file.path()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
