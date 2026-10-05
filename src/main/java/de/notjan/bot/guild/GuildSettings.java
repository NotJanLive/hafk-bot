package de.notjan.bot.guild;

import java.util.Set;

/**
 * Core per-guild configuration. Feature modules keep their own tables keyed by {@code guild_id}.
 *
 * @param dashboardRoleIds roles that may use the dashboard in addition to admins / "Manage Server"
 * @param setupCompleted   whether the first-time setup in the dashboard was finished
 */
public record GuildSettings(long guildId, Set<Long> dashboardRoleIds, boolean setupCompleted) {

    public GuildSettings {
        dashboardRoleIds = Set.copyOf(dashboardRoleIds);
    }

    public static GuildSettings defaults(long guildId) {
        return new GuildSettings(guildId, Set.of(), false);
    }

    public GuildSettings withDashboardRoles(Set<Long> roleIds) {
        return new GuildSettings(guildId, roleIds, setupCompleted);
    }

    public GuildSettings withSetupCompleted(boolean completed) {
        return new GuildSettings(guildId, dashboardRoleIds, completed);
    }
}
