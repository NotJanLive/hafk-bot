package de.notjan.bot.guild;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.guild.GuildSettingsValidator.InvalidSettingsException;
import net.dv8tion.jda.api.entities.Guild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The single write path for core settings: validate against the live guild, persist, audit.
 * Shared by the dashboard API and the /setup panel so both behave identically.
 */
public final class SettingsUpdater {

    private final GuildSettingsService settings;
    private final AuditLogService audit;

    public SettingsUpdater(GuildSettingsService settings, AuditLogService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public GuildSettings current(long guildId) {
        return settings.get(guildId);
    }

    /**
     * @throws InvalidSettingsException if the resulting settings reference unusable channels or roles
     */
    public GuildSettings apply(Guild guild, long userId, Source source, UnaryOperator<GuildSettings> change) {
        GuildSettings before = settings.get(guild.getIdLong());
        List<String> errors = GuildSettingsValidator.validate(guild, change.apply(before));
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }

        GuildSettings after = settings.update(guild.getIdLong(), change);
        List<String> changes = describeChanges(before, after);
        if (!changes.isEmpty()) {
            audit.record(guild, userId, source, "settings.update", String.join("\n", changes), details(after));
        }
        return after;
    }

    static List<String> describeChanges(GuildSettings before, GuildSettings after) {
        List<String> changes = new ArrayList<>();
        if (!before.dashboardRoleIds().equals(after.dashboardRoleIds())) {
            String roles = after.dashboardRoleIds().isEmpty()
                    ? "keine"
                    : after.dashboardRoleIds().stream().sorted().map(id -> "<@&" + id + ">").collect(Collectors.joining(", "));
            changes.add("Dashboard-Rollen: " + roles);
        }
        if (!before.setupCompleted() && after.setupCompleted()) {
            changes.add("Setup abgeschlossen");
        }
        return changes;
    }

    private static Map<String, Object> details(GuildSettings settings) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dashboardRoleIds", settings.dashboardRoleIds().stream().sorted().map(String::valueOf).toList());
        details.put("setupCompleted", settings.setupCompleted());
        return details;
    }
}
