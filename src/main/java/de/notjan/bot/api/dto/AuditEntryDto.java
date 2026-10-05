package de.notjan.bot.api.dto;

import de.notjan.bot.audit.AuditEntry;

import java.time.Instant;

public record AuditEntryDto(String id, String userId, String source, String action, String summary, Instant createdAt) {

    public static AuditEntryDto of(AuditEntry entry) {
        return new AuditEntryDto(
                String.valueOf(entry.id()),
                String.valueOf(entry.userId()),
                entry.source().name(),
                entry.action(),
                entry.summary(),
                entry.createdAt());
    }
}
