package de.notjan.bot.guild;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks settings against the live guild state, so no references to missing or unusable roles are stored.
 */
public final class GuildSettingsValidator {

    public static final int MAX_DASHBOARD_ROLES = 25;

    private GuildSettingsValidator() {
    }

    public static List<String> validate(Guild guild, GuildSettings settings) {
        List<String> errors = new ArrayList<>();

        if (settings.dashboardRoleIds().size() > MAX_DASHBOARD_ROLES) {
            errors.add("Es sind maximal " + MAX_DASHBOARD_ROLES + " Dashboard-Rollen erlaubt.");
        }
        for (long roleId : settings.dashboardRoleIds()) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                errors.add("Die Rolle " + roleId + " existiert nicht.");
            } else if (role.isPublicRole()) {
                errors.add("@everyone kann nicht als Dashboard-Rolle verwendet werden.");
            } else if (role.isManaged()) {
                errors.add("Die Rolle " + role.getName() + " gehört zu einer Integration und kann nicht verwendet werden.");
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
