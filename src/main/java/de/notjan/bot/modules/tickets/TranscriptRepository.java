package de.notjan.bot.modules.tickets;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class TranscriptRepository {

    public enum AssetKind {
        ATTACHMENT,
        EMOJI,
        STICKER,
        AVATAR
    }

    public enum AssetStatus {
        PENDING,
        STORED,
        TOO_LARGE,
        FAILED
    }

    record StoredMessage(long id, long ticketId, long messageId, long authorId, boolean internal, Instant createdAt, Instant editedAt,
                         Instant deletedAt, String data) {
    }

    record Asset(long id, long ticketId, AssetKind kind, String key, String fileName, String contentType, long size, String storagePath,
                 AssetStatus status) {
    }

    private static final RowMapper<StoredMessage> MESSAGE = (rs, ctx) -> new StoredMessage(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getLong("message_id"),
            rs.getLong("author_id"),
            rs.getBoolean("internal"),
            rs.getTimestamp("created_at").toInstant(),
            instant(rs.getTimestamp("edited_at")),
            instant(rs.getTimestamp("deleted_at")),
            rs.getString("data"));

    private static final RowMapper<Asset> ASSET = (rs, ctx) -> new Asset(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            AssetKind.valueOf(rs.getString("kind")),
            rs.getString("asset_key"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size"),
            rs.getString("storage_path"),
            AssetStatus.valueOf(rs.getString("status")));

    private final Jdbi jdbi;

    public TranscriptRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    Optional<StoredMessage> message(long messageId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_messages WHERE message_id = :messageId")
                .bind("messageId", messageId)
                .map(MESSAGE)
                .findOne());
    }

    void insertMessage(long ticketId, long messageId, long authorId, boolean internal, Instant createdAt, Instant editedAt, String data) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT IGNORE INTO ticket_messages (ticket_id, message_id, author_id, internal, created_at, edited_at, data)
                        VALUES (:ticketId, :messageId, :authorId, :internal, :createdAt, :editedAt, :data)""")
                .bind("ticketId", ticketId)
                .bind("messageId", messageId)
                .bind("authorId", authorId)
                .bind("internal", internal)
                .bind("createdAt", Timestamp.from(createdAt))
                .bind("editedAt", editedAt == null ? null : Timestamp.from(editedAt))
                .bind("data", data)
                .execute());
    }

    void updateMessage(long messageId, Instant editedAt, String data) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE ticket_messages SET edited_at = :editedAt, data = :data WHERE message_id = :messageId")
                .bind("messageId", messageId)
                .bind("editedAt", editedAt == null ? null : Timestamp.from(editedAt))
                .bind("data", data)
                .execute());
    }

    void markDeleted(List<Long> messageIds, Instant at) {
        if (messageIds.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE ticket_messages SET deleted_at = :at WHERE message_id IN (<ids>) AND deleted_at IS NULL""")
                .bindList("ids", messageIds)
                .bind("at", Timestamp.from(at))
                .execute());
    }

    List<StoredMessage> messages(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_messages WHERE ticket_id = :ticketId ORDER BY created_at, message_id")
                .bind("ticketId", ticketId)
                .map(MESSAGE)
                .list());
    }

    int countVisible(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT COUNT(*) FROM ticket_messages WHERE ticket_id = :ticketId AND internal = FALSE AND deleted_at IS NULL""")
                .bind("ticketId", ticketId)
                .mapTo(Integer.class)
                .one());
    }

    List<Long> participants(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT author_id FROM ticket_messages WHERE ticket_id = :ticketId AND internal = FALSE
                        GROUP BY author_id ORDER BY MIN(created_at)""")
                .bind("ticketId", ticketId)
                .mapTo(Long.class)
                .list());
    }

    Optional<Asset> asset(long ticketId, AssetKind kind, String key) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT * FROM ticket_assets WHERE ticket_id = :ticketId AND kind = :kind AND asset_key = :key""")
                .bind("ticketId", ticketId)
                .bind("kind", kind.name())
                .bind("key", key)
                .map(ASSET)
                .findOne());
    }

    Optional<Asset> asset(long id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_assets WHERE id = :id").bind("id", id).map(ASSET).findOne());
    }

    List<Asset> assets(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_assets WHERE ticket_id = :ticketId")
                .bind("ticketId", ticketId)
                .map(ASSET)
                .list());
    }

    Optional<Long> reserveAsset(long ticketId, AssetKind kind, String key, String fileName, String contentType, long size) {
        return jdbi.withHandle(handle -> {
            int inserted = handle.createUpdate("""
                            INSERT IGNORE INTO ticket_assets (ticket_id, kind, asset_key, file_name, content_type, size, status)
                            VALUES (:ticketId, :kind, :key, :fileName, :contentType, :size, 'PENDING')""")
                    .bind("ticketId", ticketId)
                    .bind("kind", kind.name())
                    .bind("key", key)
                    .bind("fileName", fileName.length() > 255 ? fileName.substring(0, 255) : fileName)
                    .bind("contentType", contentType == null || contentType.length() > 100 ? null : contentType)
                    .bind("size", size)
                    .execute();
            if (inserted == 0) {
                return Optional.empty();
            }
            return handle.createQuery("SELECT id FROM ticket_assets WHERE ticket_id = :ticketId AND kind = :kind AND asset_key = :key")
                    .bind("ticketId", ticketId)
                    .bind("kind", kind.name())
                    .bind("key", key)
                    .mapTo(Long.class)
                    .findOne();
        });
    }

    void finishAsset(long id, AssetStatus status, String storagePath, long size, String contentType) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE ticket_assets SET status = :status, storage_path = :path, size = :size,
                            content_type = COALESCE(:contentType, content_type) WHERE id = :id""")
                .bind("id", id)
                .bind("status", status.name())
                .bind("path", storagePath)
                .bind("size", size)
                .bind("contentType", contentType == null || contentType.length() > 100 ? null : contentType)
                .execute());
    }

    int countPending(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM ticket_assets WHERE ticket_id = :ticketId AND status = 'PENDING'")
                .bind("ticketId", ticketId)
                .mapTo(Integer.class)
                .one());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
