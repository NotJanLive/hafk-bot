package de.notjan.bot.audit;

import java.time.Instant;

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
