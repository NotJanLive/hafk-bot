package de.notjan.bot.api.dto;

import de.notjan.bot.guild.GuildSettings;

import java.util.List;

public record SettingsDto(List<String> dashboardRoleIds, boolean setupCompleted) {

    public static SettingsDto of(GuildSettings settings) {
        return new SettingsDto(
                settings.dashboardRoleIds().stream().sorted().map(String::valueOf).toList(),
                settings.setupCompleted());
    }
}
