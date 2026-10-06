package de.notjan.bot.api.controller;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.dto.GuildDto;
import de.notjan.bot.guild.GuildSettingsService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.javalin.http.Context;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.ApplicationTeam;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.TeamMember;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.get;

public final class SystemController implements ApiController {

    private static final Set<TeamMember.RoleType> INVITER_ROLES =
            EnumSet.of(TeamMember.RoleType.OWNER, TeamMember.RoleType.ADMIN, TeamMember.RoleType.DEVELOPER);

    private final JDA jda;
    private final AccessService access;
    private final GuildSettingsService settings;
    private final Cache<String, InvitePolicy> invitePolicy = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

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
        InvitePolicy policy = invitePolicy.get("application", key -> loadInvitePolicy());
        ctx.json(new BotInfo(
                self.getId(),
                self.getName(),
                self.getEffectiveAvatarUrl(),
                jda.getGuilds().stream().map(ISnowflake::getId).toList(),
                policy.publicBot(),
                policy.inviterIds()));
    }

    private InvitePolicy loadInvitePolicy() {
        ApplicationInfo info = jda.retrieveApplicationInfo().complete();
        ApplicationTeam team = info.getTeam();
        List<String> inviters = team == null
                ? List.of(info.getOwner().getId())
                : team.getMembers().stream()
                        .filter(member -> member.getMembershipState() == TeamMember.MembershipState.ACCEPTED)
                        .filter(member -> INVITER_ROLES.contains(member.getRoleType()) || member.getUser().getIdLong() == team.getOwnerIdLong())
                        .map(member -> member.getUser().getId())
                        .toList();
        return new InvitePolicy(info.isBotPublic(), inviters);
    }

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

    record BotInfo(String id, String username, String avatarUrl, List<String> guildIds, boolean publicBot, List<String> inviterIds) {
    }

    record InvitePolicy(boolean publicBot, List<String> inviterIds) {
    }
}
