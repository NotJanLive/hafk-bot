package de.notjan.bot.access;

import net.dv8tion.jda.api.Permission;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public final class DashboardAccess {

    public enum Grant {
        OWNER,
        ADMINISTRATOR,
        MANAGE_SERVER,
        DASHBOARD_ROLE
    }

    private DashboardAccess() {
    }

    public static Optional<Grant> evaluate(
            boolean owner,
            Set<Permission> permissions,
            Collection<Long> memberRoleIds,
            Set<Long> dashboardRoleIds
    ) {
        if (owner) {
            return Optional.of(Grant.OWNER);
        }
        if (permissions.contains(Permission.ADMINISTRATOR)) {
            return Optional.of(Grant.ADMINISTRATOR);
        }
        if (permissions.contains(Permission.MANAGE_SERVER)) {
            return Optional.of(Grant.MANAGE_SERVER);
        }
        if (memberRoleIds.stream().anyMatch(dashboardRoleIds::contains)) {
            return Optional.of(Grant.DASHBOARD_ROLE);
        }
        return Optional.empty();
    }
}
