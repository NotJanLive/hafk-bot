package de.notjan.bot.guild;

import java.util.Optional;
import java.util.Set;

/**
 * Core per-guild configuration. Feature modules keep their own tables keyed by {@code guild_id}.
 *
 * @param logChannelId     channel for audit messages, {@code null} if disabled
 * @param dashboardRoleIds roles that may use the dashboard in addition to admins / "Manage Server"
 */
public record GuildSettings(long guildId, Long logChannelId, Set<Long> dashboardRoleIds, boolean setupCompleted) {

    public GuildSettings {
        dashboardRoleIds = Set.copyOf(dashboardRoleIds);
    }

    public static GuildSettings defaults(long guildId) {
        return new GuildSettings(guildId, null, Set.of(), false);
    }

    public Optional<Long> logChannel() {
        return Optional.ofNullable(logChannelId);
    }

    public GuildSettings withLogChannel(Long channelId) {
        return new GuildSettings(guildId, channelId, dashboardRoleIds, setupCompleted);
    }

    public GuildSettings withDashboardRoles(Set<Long> roleIds) {
        return new GuildSettings(guildId, logChannelId, roleIds, setupCompleted);
    }

    public GuildSettings withSetupCompleted(boolean completed) {
        return new GuildSettings(guildId, logChannelId, dashboardRoleIds, completed);
    }
}
