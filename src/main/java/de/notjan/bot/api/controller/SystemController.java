package de.notjan.bot.api.controller;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.dto.GuildDto;
import de.notjan.bot.guild.GuildSettingsService;
import io.javalin.http.Context;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.SelfUser;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.get;

public final class SystemController implements ApiController {

    private final JDA jda;
    private final AccessService access;
    private final GuildSettingsService settings;

    public SystemController(JDA jda, AccessService access, GuildSettingsService settings) {
        this.jda = jda;
        this.access = access;
        this.settings = settings;
    }

    @Override
    public void addEndpoints() {
        get("/health", this::health);
        get("/bot", this::bot);
        get("/users/{userId}/guilds", this::userGuilds);
    }

    private void health(Context ctx) {
        ctx.json(new Health("ok", jda.getStatus().name(), jda.getGuilds().size()));
    }

    private void bot(Context ctx) {
        SelfUser self = jda.getSelfUser();
        ctx.json(new BotInfo(
                self.getId(),
                self.getName(),
                self.getEffectiveAvatarUrl(),
                jda.getGuilds().stream().map(ISnowflake::getId).toList()));
    }

    /**
     * Guilds the user may manage. {@code ?candidates=id,id} narrows the check to the guilds the
     * dashboard already knows the user is in, which avoids needless member lookups.
     */
    private void userGuilds(Context ctx) {
        long userId = GuildGuard.snowflake(ctx.pathParam("userId"), "userId");
        Optional<Set<String>> candidates = Optional.ofNullable(ctx.queryParam("candidates"))
                .map(value -> Arrays.stream(value.split(",")).map(String::strip).collect(Collectors.toSet()));

        List<GuildDto.Summary> guilds = jda.getGuilds().stream()
                .filter(guild -> candidates.map(ids -> ids.contains(guild.getId())).orElse(true))
                .flatMap(guild -> access.check(guild, userId).map(grant -> summary(guild, grant)).stream())
                .toList();
        ctx.json(guilds);
    }

    private GuildDto.Summary summary(Guild guild, de.notjan.bot.access.DashboardAccess.Grant grant) {
        return GuildDto.Summary.of(guild, settings.get(guild.getIdLong()).setupCompleted(), grant);
    }

    record Health(String status, String gateway, int guilds) {
    }

    record BotInfo(String id, String username, String avatarUrl, List<String> guildIds) {
    }
}
