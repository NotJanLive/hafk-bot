package de.notjan.bot.audit;

import java.time.Instant;

/**
 * A single configuration change, shown in the dashboard and mirrored to the guild's log channel.
 *
 * @param details JSON object with action-specific data
 */
public record AuditEntry(
        long id,
        long guildId,
        long userId,
        Source source,
        String action,
        String summary,
        String details,
        Instant createdAt
) {

    public enum Source {
        DISCORD,
        DASHBOARD
    }
}
