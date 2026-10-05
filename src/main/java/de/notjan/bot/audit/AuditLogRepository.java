package de.notjan.bot.audit;

import de.notjan.bot.audit.AuditEntry.Source;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

public final class AuditLogRepository {

    private final Jdbi jdbi;

    public AuditLogRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void insert(long guildId, long userId, Source source, String action, String summary, String detailsJson) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO audit_log (guild_id, user_id, source, action, summary, details)
                        VALUES (:guildId, :userId, :source, :action, :summary, :details)""")
                .bind("guildId", guildId)
                .bind("userId", userId)
                .bind("source", source.name())
                .bind("action", action)
                .bind("summary", summary)
                .bind("details", detailsJson)
                .execute());
    }

    public List<AuditEntry> recent(long guildId, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, guild_id, user_id, source, action, summary, details, created_at
                        FROM audit_log
                        WHERE guild_id = :guildId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit""")
                .bind("guildId", guildId)
                .bind("limit", limit)
                .map((rs, ctx) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getLong("guild_id"),
                        rs.getLong("user_id"),
                        Source.valueOf(rs.getString("source")),
                        rs.getString("action"),
                        rs.getString("summary"),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toInstant()))
                .list());
    }
}
