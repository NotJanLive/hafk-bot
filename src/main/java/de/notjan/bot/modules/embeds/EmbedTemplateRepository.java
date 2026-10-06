package de.notjan.bot.modules.embeds;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.message.MessagePayload;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.List;
import java.util.Optional;

public final class EmbedTemplateRepository {

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final RowMapper<EmbedTemplate> mapper;

    public EmbedTemplateRepository(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.mapper = (rs, ctx) -> new EmbedTemplate(
                rs.getLong("id"),
                rs.getLong("guild_id"),
                rs.getString("name"),
                read(rs.getString("payload")),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public List<EmbedTemplate> list(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM embed_templates WHERE guild_id = :guildId ORDER BY name")
                .bind("guildId", guildId)
                .map(mapper)
                .list());
    }

    public Optional<EmbedTemplate> find(long guildId, long id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM embed_templates WHERE guild_id = :guildId AND id = :id")
                .bind("guildId", guildId)
                .bind("id", id)
                .map(mapper)
                .findOne());
    }

    public Optional<EmbedTemplate> findByName(long guildId, String name) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM embed_templates WHERE guild_id = :guildId AND name = :name")
                .bind("guildId", guildId)
                .bind("name", name)
                .map(mapper)
                .findOne());
    }

    public long insert(long guildId, String name, MessagePayload payload, long createdBy) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        INSERT INTO embed_templates (guild_id, name, payload, created_by)
                        VALUES (:guildId, :name, :payload, :createdBy)""")
                .bind("guildId", guildId)
                .bind("name", name)
                .bind("payload", write(payload))
                .bind("createdBy", createdBy)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    public void update(long id, String name, MessagePayload payload) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE embed_templates SET name = :name, payload = :payload WHERE id = :id")
                .bind("id", id)
                .bind("name", name)
                .bind("payload", write(payload))
                .execute());
    }

    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM embed_templates WHERE id = :id").bind("id", id).execute());
    }

    public long count(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM embed_templates WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .one());
    }

    public void deleteAll(long guildId) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM embed_templates WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .execute());
    }

    private String write(MessagePayload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not serializable", e);
        }
    }

    private MessagePayload read(String value) {
        try {
            return json.readValue(value, MessagePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload is not valid JSON", e);
        }
    }
}
