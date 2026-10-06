package de.notjan.bot.message;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class BotChannelRepository {

    public record BotChannel(long id, long guildId, long channelId, String module, String label, Instant createdAt) {
    }

    private static final RowMapper<BotChannel> MAPPER = (rs, ctx) -> new BotChannel(
            rs.getLong("id"),
            rs.getLong("guild_id"),
            rs.getLong("channel_id"),
            rs.getString("module"),
            rs.getString("label"),
            rs.getTimestamp("created_at").toInstant());

    private final Jdbi jdbi;

    public BotChannelRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void insert(long guildId, long channelId, String module, String label) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO bot_channels (guild_id, channel_id, module, label)
                        VALUES (:guildId, :channelId, :module, :label)""")
                .bind("guildId", guildId)
                .bind("channelId", channelId)
                .bind("module", module)
                .bind("label", label)
                .execute());
    }

    public List<BotChannel> list(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM bot_channels WHERE guild_id = :guildId ORDER BY created_at")
                .bind("guildId", guildId)
                .map(MAPPER)
                .list());
    }

    public Optional<BotChannel> find(long guildId, long id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM bot_channels WHERE guild_id = :guildId AND id = :id")
                .bind("guildId", guildId)
                .bind("id", id)
                .map(MAPPER)
                .findOne());
    }

    public void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_channels WHERE id = :id").bind("id", id).execute());
    }

    public void deleteByChannel(long channelId) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM bot_channels WHERE channel_id = :channelId")
                .bind("channelId", channelId)
                .execute());
    }
}
