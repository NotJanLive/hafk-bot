package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Mode;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Option;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Type;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;

public final class ReactionRoleRepository {

    record PanelRow(long id, long guildId, long messageRef, Type type, Mode mode, List<Option> options) {
    }

    private final Jdbi jdbi;

    public ReactionRoleRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    long insert(long guildId, long messageRef, Type type, Mode mode, List<Option> options) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createUpdate("""
                            INSERT INTO reaction_role_panels (guild_id, message_ref, type, mode)
                            VALUES (:guildId, :messageRef, :type, :mode)""")
                    .bind("guildId", guildId)
                    .bind("messageRef", messageRef)
                    .bind("type", type.name())
                    .bind("mode", mode.name())
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            insertOptions(handle, id, options);
            return id;
        });
    }

    void update(long id, Type type, Mode mode, List<Option> options) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("UPDATE reaction_role_panels SET type = :type, mode = :mode WHERE id = :id")
                    .bind("id", id)
                    .bind("type", type.name())
                    .bind("mode", mode.name())
                    .execute();
            handle.createUpdate("DELETE FROM reaction_role_options WHERE panel_id = :id").bind("id", id).execute();
            insertOptions(handle, id, options);
        });
    }

    List<PanelRow> list(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM reaction_role_panels WHERE guild_id = :guildId ORDER BY id DESC")
                .bind("guildId", guildId)
                .map((rs, ctx) -> rs.getLong("id"))
                .list()
                .stream()
                .map(id -> load(handle, id).orElseThrow())
                .toList());
    }

    Optional<PanelRow> find(long guildId, long id) {
        return jdbi.withHandle(handle -> load(handle, id).filter(row -> row.guildId() == guildId));
    }

    Optional<PanelRow> findByDiscordMessage(long messageId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT p.id FROM reaction_role_panels p
                        JOIN bot_messages m ON m.id = p.message_ref
                        WHERE m.message_id = :messageId""")
                .bind("messageId", messageId)
                .mapTo(Long.class)
                .findOne()
                .flatMap(id -> load(handle, id)));
    }

    List<Long> reactionMessageIds() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT m.message_id FROM reaction_role_panels p
                        JOIN bot_messages m ON m.id = p.message_ref
                        WHERE p.type = 'REACTIONS'""")
                .mapTo(Long.class)
                .list());
    }

    long count(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM reaction_role_panels WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .one());
    }

    private static Optional<PanelRow> load(Handle handle, long id) {
        List<Option> options = handle.createQuery("""
                        SELECT role_id, label, emoji, description, style FROM reaction_role_options
                        WHERE panel_id = :id ORDER BY position""")
                .bind("id", id)
                .map((rs, ctx) -> new Option(rs.getLong("role_id"), rs.getString("label"), rs.getString("emoji"),
                        rs.getString("description"), rs.getString("style")))
                .list();
        return handle.createQuery("SELECT * FROM reaction_role_panels WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new PanelRow(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("message_ref"),
                        Type.valueOf(rs.getString("type")), Mode.valueOf(rs.getString("mode")), options))
                .findOne();
    }

    private static void insertOptions(Handle handle, long panelId, List<Option> options) {
        if (options.isEmpty()) {
            return;
        }
        var batch = handle.prepareBatch("""
                INSERT INTO reaction_role_options (panel_id, role_id, label, emoji, description, style, position)
                VALUES (:panelId, :roleId, :label, :emoji, :description, :style, :position)""");
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            batch.bind("panelId", panelId)
                    .bind("roleId", option.roleId())
                    .bind("label", option.label())
                    .bind("emoji", option.emoji())
                    .bind("description", option.description())
                    .bind("style", option.style())
                    .bind("position", i)
                    .add();
        }
        batch.execute();
    }
}
