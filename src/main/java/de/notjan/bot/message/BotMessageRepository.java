package de.notjan.bot.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class BotMessageRepository {

    private static final String COLUMNS = "id, guild_id, channel_id, message_id, module, label, payload, created_by, created_at, updated_at";

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final RowMapper<BotMessage> mapper;

    public BotMessageRepository(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.mapper = (rs, ctx) -> new BotMessage(
                rs.getLong("id"),
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                rs.getLong("message_id"),
                rs.getString("module"),
                rs.getString("label"),
                readPayload(rs.getString("payload")),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public long insert(long guildId, long channelId, long messageId, String module, String label, MessagePayload payload, long createdBy) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        INSERT INTO bot_messages (guild_id, channel_id, message_id, module, label, payload, created_by)
                        VALUES (:guildId, :channelId, :messageId, :module, :label, :payload, :createdBy)""")
                .bind("guildId", guildId)
                .bind("channelId", channelId)
                .bind("messageId", messageId)
                .bind("module", module)
                .bind("label", label)
                .bind("payload", writePayload(payload))
                .bind("createdBy", createdBy)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    public void update(long id, String label, MessagePayload payload) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE bot_messages SET label = :label, payload = :payload WHERE id = :id")
                .bind("id", id)
                .bind("label", label)
                .bind("payload", writePayload(payload))
                .execute());
    }

    public Optional<BotMessage> find(long guildId, long id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLUMNS + " FROM bot_messages WHERE guild_id = :guildId AND id = :id")
                .bind("guildId", guildId)
                .bind("id", id)
                .map(mapper)
                .findOne());
    }

    public Optional<BotMessage> findByMessageId(long messageId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLUMNS + " FROM bot_messages WHERE message_id = :messageId")
                .bind("messageId", messageId)
                .map(mapper)
                .findOne());
    }

    public List<BotMessage> list(long guildId, String module) {
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery("SELECT " + COLUMNS + " FROM bot_messages WHERE guild_id = :guildId"
                            + (module == null ? "" : " AND module = :module") + " ORDER BY created_at DESC, id DESC")
                    .bind("guildId", guildId);
            if (module != null) {
                query.bind("module", module);
            }
            return query.map(mapper).list();
        });
    }

    public long count(long guildId, String module) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM bot_messages WHERE guild_id = :guildId AND module = :module")
                .bind("guildId", guildId)
                .bind("module", module)
                .mapTo(Long.class)
                .one());
    }

    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_messages WHERE id = :id").bind("id", id).execute());
    }

    public void deleteByMessageIds(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_messages WHERE message_id IN (<ids>)")
                .bindList("ids", List.copyOf(messageIds))
                .execute());
    }

    public void deleteByChannel(long channelId) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_messages WHERE channel_id = :channelId")
                .bind("channelId", channelId)
                .execute());
    }

    public void deleteByModule(long guildId, String module) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_messages WHERE guild_id = :guildId AND module = :module")
                .bind("guildId", guildId)
                .bind("module", module)
                .execute());
    }

    private String writePayload(MessagePayload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not serializable", e);
        }
    }

    private MessagePayload readPayload(String value) {
        try {
            return json.readValue(value, MessagePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload is not valid JSON", e);
        }
    }
}
