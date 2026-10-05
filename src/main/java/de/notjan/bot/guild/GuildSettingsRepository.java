package de.notjan.bot.guild;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.HashSet;
import java.util.Set;

public final class GuildSettingsRepository {

    private final Jdbi jdbi;

    public GuildSettingsRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Loads the settings of a guild, creating the default row on first access. */
    public GuildSettings findOrCreate(long guildId) {
        return jdbi.inTransaction(handle -> {
            handle.createUpdate("INSERT IGNORE INTO guild_settings (guild_id) VALUES (:guildId)")
                    .bind("guildId", guildId)
                    .execute();
            return load(handle, guildId);
        });
    }

    public void save(GuildSettings settings) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE guild_settings
                            SET log_channel_id = :logChannelId, setup_completed = :setupCompleted
                            WHERE guild_id = :guildId""")
                    .bind("guildId", settings.guildId())
                    .bind("logChannelId", settings.logChannelId())
                    .bind("setupCompleted", settings.setupCompleted())
                    .execute();

            handle.createUpdate("DELETE FROM guild_dashboard_roles WHERE guild_id = :guildId")
                    .bind("guildId", settings.guildId())
                    .execute();

            if (!settings.dashboardRoleIds().isEmpty()) {
                var batch = handle.prepareBatch("INSERT INTO guild_dashboard_roles (guild_id, role_id) VALUES (:guildId, :roleId)");
                settings.dashboardRoleIds().forEach(roleId -> batch.bind("guildId", settings.guildId()).bind("roleId", roleId).add());
                batch.execute();
            }
        });
    }

    private static GuildSettings load(Handle handle, long guildId) {
        Set<Long> roleIds = new HashSet<>(handle.createQuery("SELECT role_id FROM guild_dashboard_roles WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .list());

        return handle.createQuery("SELECT log_channel_id, setup_completed FROM guild_settings WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .map((rs, ctx) -> new GuildSettings(
                        guildId,
                        rs.getObject("log_channel_id", Long.class),
                        roleIds,
                        rs.getBoolean("setup_completed")))
                .one();
    }
}
