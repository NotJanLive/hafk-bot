package de.notjan.bot.guild;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsUpdaterTest {

    private static final GuildSettings BASE = GuildSettings.defaults(1L);

    @Test
    void reportsNothingWhenUnchanged() {
        assertEquals(List.of(), SettingsUpdater.describeChanges(BASE, BASE));
    }

    @Test
    void describesLogChannelChanges() {
        GuildSettings withChannel = BASE.withLogChannel(42L);

        assertEquals(List.of("Log-Kanal auf <#42> gesetzt"), SettingsUpdater.describeChanges(BASE, withChannel));
        assertEquals(List.of("Log-Kanal deaktiviert"), SettingsUpdater.describeChanges(withChannel, BASE));
    }

    @Test
    void describesDashboardRolesSorted() {
        GuildSettings withRoles = BASE.withDashboardRoles(Set.of(30L, 10L));

        assertEquals(List.of("Dashboard-Rollen: <@&10>, <@&30>"), SettingsUpdater.describeChanges(BASE, withRoles));
        assertEquals(List.of("Dashboard-Rollen: keine"), SettingsUpdater.describeChanges(withRoles, BASE));
    }

    @Test
    void describesSetupCompletionOnce() {
        GuildSettings completed = BASE.withSetupCompleted(true);

        assertEquals(List.of("Setup abgeschlossen"), SettingsUpdater.describeChanges(BASE, completed));
        assertEquals(List.of(), SettingsUpdater.describeChanges(completed, completed));
    }
}
