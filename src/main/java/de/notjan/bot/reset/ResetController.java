package de.notjan.bot.reset;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import io.javalin.http.Context;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;

public final class ResetController implements ApiController {

    private final GuildGuard guard;
    private final ResetService reset;

    public ResetController(GuildGuard guard, ResetService reset) {
        this.guard = guard;
        this.reset = reset;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}/reset", this::preview);
        post("/guilds/{guildId}/reset", this::execute);
    }

    private void preview(Context ctx) {
        GuildRequest request = guard.requireAdmin(ctx);
        ctx.json(reset.preview(request.guild()));
    }

    private void execute(Context ctx) {
        GuildRequest request = guard.requireAdmin(ctx);
        ResetService.Request body = ApiController.readBody(ctx, ResetService.Request.class);
        ctx.json(reset.execute(request.guild(), request.userId(), body));
    }
}
