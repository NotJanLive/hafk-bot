package de.notjan.bot.api;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.access.DashboardAccess.Grant;
import io.javalin.http.Context;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;

/**
 * Resolves the guild of a request and re-checks that the acting dashboard user may manage it.
 * The dashboard checks access too, but the bot is the source of truth.
 */
public final class GuildGuard {

    public static final String ACTING_USER_HEADER = "X-Acting-User";

    private final JDA jda;
    private final AccessService access;

    public GuildGuard(JDA jda, AccessService access) {
        this.jda = jda;
        this.access = access;
    }

    public GuildRequest require(Context ctx) {
        long guildId = snowflake(ctx.pathParam("guildId"), "guildId");
        long userId = actingUser(ctx);

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw ApiException.notFound("Der Bot ist nicht auf diesem Server.");
        }
        Grant grant = access.check(guild, userId)
                .orElseThrow(() -> ApiException.forbidden("Kein Zugriff auf diesen Server."));
        return new GuildRequest(guild, userId, grant);
    }

    public static long actingUser(Context ctx) {
        String header = ctx.header(ACTING_USER_HEADER);
        if (header == null) {
            throw ApiException.badRequest("Header " + ACTING_USER_HEADER + " fehlt.");
        }
        return snowflake(header, ACTING_USER_HEADER);
    }

    public static long snowflake(String value, String name) {
        try {
            long id = Long.parseUnsignedLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " ist keine gültige Discord-ID.");
        }
    }

    public record GuildRequest(Guild guild, long userId, Grant grant) {
    }
}
