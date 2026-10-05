package de.notjan.bot.access;

import de.notjan.bot.access.DashboardAccess.Grant;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardAccessTest {

    private static final Set<Long> DASHBOARD_ROLES = Set.of(10L, 20L);

    @Test
    void ownerAlwaysHasAccess() {
        assertEquals(Optional.of(Grant.OWNER),
                DashboardAccess.evaluate(true, EnumSet.noneOf(Permission.class), List.of(), DASHBOARD_ROLES));
    }

    @Test
    void administratorHasAccess() {
        assertEquals(Optional.of(Grant.ADMINISTRATOR),
                DashboardAccess.evaluate(false, EnumSet.of(Permission.ADMINISTRATOR), List.of(), Set.of()));
    }

    @Test
    void manageServerHasAccess() {
        assertEquals(Optional.of(Grant.MANAGE_SERVER),
                DashboardAccess.evaluate(false, EnumSet.of(Permission.MANAGE_SERVER), List.of(), Set.of()));
    }

    @Test
    void dashboardRoleGrantsAccess() {
        assertEquals(Optional.of(Grant.DASHBOARD_ROLE),
                DashboardAccess.evaluate(false, EnumSet.of(Permission.MESSAGE_SEND), List.of(5L, 20L), DASHBOARD_ROLES));
    }

    @Test
    void regularMemberIsDenied() {
        assertEquals(Optional.empty(),
                DashboardAccess.evaluate(false, EnumSet.of(Permission.MESSAGE_SEND, Permission.MANAGE_ROLES), List.of(5L), DASHBOARD_ROLES));
    }
}
