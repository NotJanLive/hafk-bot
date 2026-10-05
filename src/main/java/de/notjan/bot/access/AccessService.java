package de.notjan.bot.access;

import de.notjan.bot.access.DashboardAccess.Grant;
import de.notjan.bot.guild.GuildSettingsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.Optional;

/**
 * Resolves a member live from Discord and applies {@link DashboardAccess}. Members are kept in
 * JDA's cache (GUILD_MEMBERS intent), so role changes take effect immediately.
 */
public final class AccessService {

    private final GuildSettingsService settings;

    public AccessService(GuildSettingsService settings) {
        this.settings = settings;
    }

    public Optional<Grant> check(Guild guild, long userId) {
        return findMember(guild, userId).flatMap(member -> DashboardAccess.evaluate(
                member.isOwner(),
                member.getPermissions(),
                member.getRoles().stream().map(ISnowflake::getIdLong).toList(),
                settings.get(guild.getIdLong()).dashboardRoleIds()));
    }

    private static Optional<Member> findMember(Guild guild, long userId) {
        Member cached = guild.getMemberById(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return Optional.of(guild.retrieveMemberById(userId).complete());
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
