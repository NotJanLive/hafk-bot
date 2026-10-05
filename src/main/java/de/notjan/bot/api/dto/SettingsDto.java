package de.notjan.bot.api.dto;

import de.notjan.bot.guild.GuildSettings;

import java.util.List;

/**
 * Core guild settings as exchanged with the dashboard. {@code PUT} replaces the whole object.
 */
public record SettingsDto(String logChannelId, List<String> dashboardRoleIds, boolean setupCompleted) {

    public static SettingsDto of(GuildSettings settings) {
        return new SettingsDto(
                settings.logChannel().map(String::valueOf).orElse(null),
                settings.dashboardRoleIds().stream().sorted().map(String::valueOf).toList(),
                settings.setupCompleted());
    }
}
