package de.notjan.bot.guild;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks settings against the live guild state. Used by both the dashboard API and /setup,
 * so neither entry point can store references to missing or unusable channels and roles.
 */
public final class GuildSettingsValidator {

    public static final int MAX_DASHBOARD_ROLES = 25;

    private GuildSettingsValidator() {
    }

    public static List<String> validate(Guild guild, GuildSettings settings) {
        List<String> errors = new ArrayList<>();

        settings.logChannel().ifPresent(channelId -> {
            TextChannel channel = guild.getTextChannelById(channelId);
            if (channel == null) {
                errors.add("Der Log-Kanal existiert nicht oder ist kein Textkanal.");
            } else if (!guild.getSelfMember().hasPermission(channel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                errors.add("Der Bot kann in " + channel.getAsMention() + " keine Nachrichten senden.");
            }
        });

        if (settings.dashboardRoleIds().size() > MAX_DASHBOARD_ROLES) {
            errors.add("Es sind maximal " + MAX_DASHBOARD_ROLES + " Dashboard-Rollen erlaubt.");
        }
        for (long roleId : settings.dashboardRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                errors.add("Die Rolle " + roleId + " existiert nicht.");
            } else if (role.isPublicRole()) {
                errors.add("@everyone kann nicht als Dashboard-Rolle verwendet werden.");
            }
        }
        return errors;
    }

    public static final class InvalidSettingsException extends RuntimeException {

        private final List<String> errors;

        public InvalidSettingsException(List<String> errors) {
            super(String.join(" ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
