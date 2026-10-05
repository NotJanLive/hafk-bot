package de.notjan.bot.api.dto;

import de.notjan.bot.audit.AuditEntry;

import java.time.Instant;

/**
 * @param userName display name if the user is cached, otherwise {@code null} (the dashboard falls back to the ID)
 */
public record AuditEntryDto(
        String id,
        String userId,
        String userName,
        String source,
        String action,
        String summary,
        Instant createdAt
) {

    public static AuditEntryDto of(AuditEntry entry, String userName) {
        return new AuditEntryDto(
                String.valueOf(entry.id()),
                String.valueOf(entry.userId()),
                userName,
                entry.source().name(),
                entry.action(),
                entry.summary(),
                entry.createdAt());
    }
}
