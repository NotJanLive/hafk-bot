package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.modules.tickets.TicketConfig.AccessKind;
import de.notjan.bot.modules.tickets.TicketConfig.BlacklistEntry;
import de.notjan.bot.modules.tickets.TicketConfig.ButtonColor;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.FieldOption;
import de.notjan.bot.modules.tickets.TicketConfig.FieldType;
import de.notjan.bot.modules.tickets.TicketConfig.FormField;
import de.notjan.bot.modules.tickets.TicketConfig.PanelStyle;
import de.notjan.bot.modules.tickets.TicketConfig.RoleKind;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TicketConfigRepository {

    record PanelRow(long id, long guildId, String name, long channelId, Long messageRef, PanelStyle style, String placeholder,
                    MessagePayload payload, List<Long> categoryIds, long createdBy, Instant updatedAt) {
    }

    private static final TypeReference<List<FieldOption>> OPTIONS = new TypeReference<>() {
    };

    private final Jdbi jdbi;
    private final ObjectMapper json;

    public TicketConfigRepository(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
    }

    Settings settings(long guildId) {
        return jdbi.withHandle(handle -> {
            Set<Target> staff = access(handle, guildId, AccessKind.STAFF);
            Set<Target> viewers = access(handle, guildId, AccessKind.TRANSCRIPTS);
            Settings defaults = Settings.defaults(guildId);
            return handle.createQuery("SELECT * FROM ticket_settings WHERE guild_id = :guildId")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> new Settings(guildId,
                            rs.getObject("log_channel_id", Long.class),
                            rs.getInt("close_delay_seconds"),
                            rs.getInt("close_request_hours"),
                            rs.getBoolean("creator_can_close"),
                            rs.getBoolean("claim_lock"),
                            rs.getBoolean("transfer_to_anyone"),
                            rs.getBoolean("notes_enabled"),
                            rs.getBoolean("ratings_enabled"),
                            rs.getBoolean("dm_on_close"),
                            rs.getBoolean("creator_transcript"),
                            rs.getObject("retention_days", Integer.class),
                            staff,
                            viewers))
                    .findOne()
                    .orElseGet(() -> new Settings(guildId, null, defaults.closeDelaySeconds(), defaults.closeRequestHours(),
                            defaults.creatorCanClose(), defaults.claimLock(), defaults.transferToAnyone(), defaults.notesEnabled(),
                            defaults.ratingsEnabled(), defaults.dmOnClose(), defaults.creatorTranscript(), null, staff, viewers));
        });
    }

    void saveSettings(Settings settings) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO ticket_settings (guild_id, log_channel_id, close_delay_seconds, close_request_hours, creator_can_close,
                                                         claim_lock, transfer_to_anyone, notes_enabled, ratings_enabled, dm_on_close,
                                                         creator_transcript, retention_days)
                            VALUES (:guildId, :logChannelId, :closeDelay, :closeRequestHours, :creatorCanClose, :claimLock, :transferToAnyone,
                                    :notesEnabled, :ratingsEnabled, :dmOnClose, :creatorTranscript, :retentionDays)
                            ON DUPLICATE KEY UPDATE log_channel_id = VALUES(log_channel_id), close_delay_seconds = VALUES(close_delay_seconds),
                                close_request_hours = VALUES(close_request_hours), creator_can_close = VALUES(creator_can_close),
                                claim_lock = VALUES(claim_lock), transfer_to_anyone = VALUES(transfer_to_anyone),
                                notes_enabled = VALUES(notes_enabled), ratings_enabled = VALUES(ratings_enabled),
                                dm_on_close = VALUES(dm_on_close), creator_transcript = VALUES(creator_transcript),
                                retention_days = VALUES(retention_days)""")
                    .bind("guildId", settings.guildId())
                    .bind("logChannelId", settings.logChannelId())
                    .bind("closeDelay", settings.closeDelaySeconds())
                    .bind("closeRequestHours", settings.closeRequestHours())
                    .bind("creatorCanClose", settings.creatorCanClose())
                    .bind("claimLock", settings.claimLock())
                    .bind("transferToAnyone", settings.transferToAnyone())
                    .bind("notesEnabled", settings.notesEnabled())
                    .bind("ratingsEnabled", settings.ratingsEnabled())
                    .bind("dmOnClose", settings.dmOnClose())
                    .bind("creatorTranscript", settings.creatorTranscript())
                    .bind("retentionDays", settings.retentionDays())
                    .execute();
            handle.createUpdate("DELETE FROM ticket_access WHERE guild_id = :guildId").bind("guildId", settings.guildId()).execute();
            insertAccess(handle, settings.guildId(), AccessKind.STAFF, settings.staff());
            insertAccess(handle, settings.guildId(), AccessKind.TRANSCRIPTS, settings.transcriptViewers());
        });
    }

    int nextNumber(long guildId) {
        return jdbi.inTransaction(handle -> {
            handle.createUpdate("INSERT IGNORE INTO ticket_settings (guild_id) VALUES (:guildId)").bind("guildId", guildId).execute();
            int number = handle.createQuery("SELECT next_number FROM ticket_settings WHERE guild_id = :guildId FOR UPDATE")
                    .bind("guildId", guildId)
                    .mapTo(Integer.class)
                    .one();
            handle.createUpdate("UPDATE ticket_settings SET next_number = next_number + 1 WHERE guild_id = :guildId")
                    .bind("guildId", guildId)
                    .execute();
            return number;
        });
    }

    List<Category> categories(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id FROM ticket_categories WHERE guild_id = :guildId ORDER BY position, id")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .list()
                .stream()
                .flatMap(id -> loadCategory(handle, id).stream())
                .toList());
    }

    Optional<Category> category(long id) {
        return jdbi.withHandle(handle -> loadCategory(handle, id));
    }

    long saveCategory(Category category) {
        return jdbi.inTransaction(handle -> {
            long id = category.id();
            var update = id == 0
                    ? handle.createUpdate("""
                    INSERT INTO ticket_categories (guild_id, name, emoji, description, button_style, parent_id, channel_pattern,
                                                   welcome_payload, confirm_text, form_title, max_open, cooldown_minutes, ping_support, include_staff,
                                                   application, accept_role_id, accept_message, deny_message, close_on_decision, position)
                    VALUES (:guildId, :name, :emoji, :description, :buttonStyle, :parentId, :channelPattern, :welcome, :confirmText,
                            :formTitle, :maxOpen, :cooldown, :pingSupport, :includeStaff, :application, :acceptRoleId, :acceptMessage, :denyMessage,
                            :closeOnDecision, :position)""")
                    : handle.createUpdate("""
                    UPDATE ticket_categories SET name = :name, emoji = :emoji, description = :description, button_style = :buttonStyle,
                        parent_id = :parentId, channel_pattern = :channelPattern, welcome_payload = :welcome, confirm_text = :confirmText,
                        form_title = :formTitle, max_open = :maxOpen, cooldown_minutes = :cooldown, ping_support = :pingSupport, include_staff = :includeStaff,
                        application = :application, accept_role_id = :acceptRoleId, accept_message = :acceptMessage,
                        deny_message = :denyMessage, close_on_decision = :closeOnDecision, position = :position
                    WHERE id = :id AND guild_id = :guildId""").bind("id", id);
            update.bind("guildId", category.guildId())
                    .bind("name", category.name())
                    .bind("emoji", category.emoji())
                    .bind("description", category.description())
                    .bind("buttonStyle", category.buttonStyle().name())
                    .bind("parentId", category.parentId())
                    .bind("channelPattern", category.channelPattern())
                    .bind("welcome", category.welcome() == null ? null : write(category.welcome()))
                    .bind("confirmText", category.confirmText())
                    .bind("formTitle", category.formTitle())
                    .bind("maxOpen", category.maxOpen())
                    .bind("cooldown", category.cooldownMinutes())
                    .bind("pingSupport", category.pingSupport())
                    .bind("includeStaff", category.includeStaff())
                    .bind("application", category.application())
                    .bind("acceptRoleId", category.acceptRoleId())
                    .bind("acceptMessage", category.acceptMessage())
                    .bind("denyMessage", category.denyMessage())
                    .bind("closeOnDecision", category.closeOnDecision())
                    .bind("position", category.position());
            if (id == 0) {
                id = update.executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            } else {
                update.execute();
            }
            long categoryId = id;
            handle.createUpdate("DELETE FROM ticket_category_roles WHERE category_id = :id").bind("id", categoryId).execute();
            insertRoles(handle, categoryId, RoleKind.SUPPORT, category.supportRoleIds());
            insertRoles(handle, categoryId, RoleKind.PING, category.pingRoleIds());
            insertRoles(handle, categoryId, RoleKind.REQUIRED, category.requiredRoleIds());
            handle.createUpdate("DELETE FROM ticket_form_fields WHERE category_id = :id").bind("id", categoryId).execute();
            for (int i = 0; i < category.fields().size(); i++) {
                FormField field = category.fields().get(i);
                handle.createUpdate("""
                                INSERT INTO ticket_form_fields (category_id, position, type, label, description, placeholder, required,
                                                                min_length, max_length, min_values, max_values, options)
                                VALUES (:categoryId, :position, :type, :label, :description, :placeholder, :required, :minLength,
                                        :maxLength, :minValues, :maxValues, :options)""")
                        .bind("categoryId", categoryId)
                        .bind("position", i)
                        .bind("type", field.type().name())
                        .bind("label", field.label())
                        .bind("description", field.description())
                        .bind("placeholder", field.placeholder())
                        .bind("required", field.required())
                        .bind("minLength", field.minLength())
                        .bind("maxLength", field.maxLength())
                        .bind("minValues", field.minValues())
                        .bind("maxValues", field.maxValues())
                        .bind("options", field.options().isEmpty() ? null : write(field.options()))
                        .execute();
            }
            return categoryId;
        });
    }

    void deleteCategory(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM ticket_categories WHERE id = :id").bind("id", id).execute());
    }

    List<PanelRow> panels(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id FROM ticket_panels WHERE guild_id = :guildId ORDER BY id")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .list()
                .stream()
                .flatMap(id -> loadPanel(handle, id).stream())
                .toList());
    }

    Optional<PanelRow> panel(long id) {
        return jdbi.withHandle(handle -> loadPanel(handle, id));
    }

    List<Long> panelsWithCategory(long categoryId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT panel_id FROM ticket_panel_categories WHERE category_id = :id")
                .bind("id", categoryId)
                .mapTo(Long.class)
                .list());
    }

    long savePanel(long id, long guildId, String name, long channelId, PanelStyle style, String placeholder, MessagePayload payload,
                   List<Long> categoryIds, long userId) {
        return jdbi.inTransaction(handle -> {
            long panelId = id;
            if (panelId == 0) {
                panelId = handle.createUpdate("""
                                INSERT INTO ticket_panels (guild_id, name, channel_id, style, placeholder, payload, created_by)
                                VALUES (:guildId, :name, :channelId, :style, :placeholder, :payload, :userId)""")
                        .bind("guildId", guildId)
                        .bind("name", name)
                        .bind("channelId", channelId)
                        .bind("style", style.name())
                        .bind("placeholder", placeholder)
                        .bind("payload", write(payload))
                        .bind("userId", userId)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();
            } else {
                handle.createUpdate("""
                                UPDATE ticket_panels SET name = :name, channel_id = :channelId, style = :style, placeholder = :placeholder,
                                    payload = :payload WHERE id = :id""")
                        .bind("id", panelId)
                        .bind("name", name)
                        .bind("channelId", channelId)
                        .bind("style", style.name())
                        .bind("placeholder", placeholder)
                        .bind("payload", write(payload))
                        .execute();
                handle.createUpdate("DELETE FROM ticket_panel_categories WHERE panel_id = :id").bind("id", panelId).execute();
            }
            if (!categoryIds.isEmpty()) {
                var batch = handle.prepareBatch("""
                        INSERT INTO ticket_panel_categories (panel_id, category_id, position) VALUES (:panelId, :categoryId, :position)""");
                for (int i = 0; i < categoryIds.size(); i++) {
                    batch.bind("panelId", panelId).bind("categoryId", categoryIds.get(i)).bind("position", i).add();
                }
                batch.execute();
            }
            return panelId;
        });
    }

    void setPanelMessage(long id, Long messageRef) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE ticket_panels SET message_ref = :ref WHERE id = :id")
                .bind("id", id)
                .bind("ref", messageRef)
                .execute());
    }

    void deletePanel(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM ticket_panels WHERE id = :id").bind("id", id).execute());
    }

    List<BlacklistEntry> blacklist(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT * FROM ticket_blacklist WHERE guild_id = :guildId AND (expires_at IS NULL OR expires_at > :now)
                        ORDER BY created_at DESC""")
                .bind("guildId", guildId)
                .bind("now", Timestamp.from(Instant.now()))
                .map((rs, ctx) -> blacklistEntry(rs))
                .list());
    }

    Optional<BlacklistEntry> blacklisted(long guildId, long userId, List<Long> roleIds) {
        return blacklist(guildId).stream()
                .filter(entry -> entry.type() == TargetType.USER ? entry.targetId() == userId : roleIds.contains(entry.targetId()))
                .findFirst();
    }

    void blacklistAdd(long guildId, Target target, String reason, Instant expiresAt, long userId) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO ticket_blacklist (guild_id, target_type, target_id, reason, expires_at, created_by)
                        VALUES (:guildId, :type, :targetId, :reason, :expiresAt, :userId)
                        ON DUPLICATE KEY UPDATE reason = VALUES(reason), expires_at = VALUES(expires_at), created_by = VALUES(created_by),
                            created_at = CURRENT_TIMESTAMP(3)""")
                .bind("guildId", guildId)
                .bind("type", target.type().name())
                .bind("targetId", target.id())
                .bind("reason", reason)
                .bind("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .bind("userId", userId)
                .execute());
    }

    boolean blacklistRemove(long guildId, Target target) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        DELETE FROM ticket_blacklist WHERE guild_id = :guildId AND target_type = :type AND target_id = :targetId""")
                .bind("guildId", guildId)
                .bind("type", target.type().name())
                .bind("targetId", target.id())
                .execute() > 0);
    }

    long countConfig(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT (SELECT COUNT(*) FROM ticket_categories WHERE guild_id = :guildId)
                             + (SELECT COUNT(*) FROM ticket_panels WHERE guild_id = :guildId)
                             + (SELECT COUNT(*) FROM ticket_settings WHERE guild_id = :guildId)""")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .one());
    }

    void deleteConfig(long guildId) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM ticket_panels WHERE guild_id = :guildId").bind("guildId", guildId).execute();
            handle.createUpdate("DELETE FROM ticket_categories WHERE guild_id = :guildId").bind("guildId", guildId).execute();
            handle.createUpdate("DELETE FROM ticket_access WHERE guild_id = :guildId").bind("guildId", guildId).execute();
            handle.createUpdate("""
                            UPDATE ticket_settings SET log_channel_id = NULL, close_delay_seconds = 5, close_request_hours = 24,
                                creator_can_close = TRUE, claim_lock = FALSE, transfer_to_anyone = FALSE, notes_enabled = TRUE,
                                ratings_enabled = TRUE, dm_on_close = TRUE, creator_transcript = FALSE, retention_days = NULL
                            WHERE guild_id = :guildId""")
                    .bind("guildId", guildId)
                    .execute();
        });
    }

    long countBlacklist(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM ticket_blacklist WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .one());
    }

    void deleteBlacklist(long guildId) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM ticket_blacklist WHERE guild_id = :guildId").bind("guildId", guildId).execute());
    }

    private static Set<Target> access(Handle handle, long guildId, AccessKind kind) {
        return new HashSet<>(handle.createQuery("SELECT target_type, target_id FROM ticket_access WHERE guild_id = :guildId AND kind = :kind")
                .bind("guildId", guildId)
                .bind("kind", kind.name())
                .map((rs, ctx) -> new Target(TargetType.valueOf(rs.getString("target_type")), rs.getLong("target_id")))
                .list());
    }

    private static void insertAccess(Handle handle, long guildId, AccessKind kind, Set<Target> targets) {
        if (targets.isEmpty()) {
            return;
        }
        var batch = handle.prepareBatch("""
                INSERT INTO ticket_access (guild_id, kind, target_type, target_id) VALUES (:guildId, :kind, :type, :targetId)""");
        targets.forEach(target -> batch.bind("guildId", guildId).bind("kind", kind.name()).bind("type", target.type().name())
                .bind("targetId", target.id()).add());
        batch.execute();
    }

    private static void insertRoles(Handle handle, long categoryId, RoleKind kind, Set<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        var batch = handle.prepareBatch("INSERT INTO ticket_category_roles (category_id, role_id, kind) VALUES (:id, :roleId, :kind)");
        roleIds.forEach(roleId -> batch.bind("id", categoryId).bind("roleId", roleId).bind("kind", kind.name()).add());
        batch.execute();
    }

    private Optional<Category> loadCategory(Handle handle, long id) {
        Set<Long> support = new HashSet<>();
        Set<Long> ping = new HashSet<>();
        Set<Long> required = new HashSet<>();
        handle.createQuery("SELECT role_id, kind FROM ticket_category_roles WHERE category_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> Map.entry(RoleKind.valueOf(rs.getString("kind")), rs.getLong("role_id")))
                .forEach(row -> (switch (row.getKey()) {
                    case SUPPORT -> support;
                    case PING -> ping;
                    case REQUIRED -> required;
                }).add(row.getValue()));
        List<FormField> fields = handle.createQuery("SELECT * FROM ticket_form_fields WHERE category_id = :id ORDER BY position")
                .bind("id", id)
                .map((rs, ctx) -> new FormField(
                        rs.getLong("id"),
                        FieldType.valueOf(rs.getString("type")),
                        rs.getString("label"),
                        rs.getString("description"),
                        rs.getString("placeholder"),
                        rs.getBoolean("required"),
                        rs.getObject("min_length", Integer.class),
                        rs.getObject("max_length", Integer.class),
                        rs.getObject("min_values", Integer.class),
                        rs.getObject("max_values", Integer.class),
                        readOptions(rs.getString("options"))))
                .list();
        return handle.createQuery("SELECT * FROM ticket_categories WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new Category(
                        rs.getLong("id"),
                        rs.getLong("guild_id"),
                        rs.getString("name"),
                        rs.getString("emoji"),
                        rs.getString("description"),
                        ButtonColor.valueOf(rs.getString("button_style")),
                        rs.getObject("parent_id", Long.class),
                        rs.getString("channel_pattern"),
                        rs.getString("welcome_payload") == null ? null : readPayload(rs.getString("welcome_payload")),
                        rs.getString("confirm_text"),
                        rs.getString("form_title"),
                        rs.getInt("max_open"),
                        rs.getInt("cooldown_minutes"),
                        rs.getBoolean("ping_support"),
                        rs.getBoolean("include_staff"),
                        rs.getBoolean("application"),
                        rs.getObject("accept_role_id", Long.class),
                        rs.getString("accept_message"),
                        rs.getString("deny_message"),
                        rs.getBoolean("close_on_decision"),
                        rs.getInt("position"),
                        support,
                        ping,
                        required,
                        fields))
                .findOne();
    }

    private Optional<PanelRow> loadPanel(Handle handle, long id) {
        List<Long> categories = handle.createQuery("SELECT category_id FROM ticket_panel_categories WHERE panel_id = :id ORDER BY position")
                .bind("id", id)
                .mapTo(Long.class)
                .list();
        return handle.createQuery("SELECT * FROM ticket_panels WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new PanelRow(
                        rs.getLong("id"),
                        rs.getLong("guild_id"),
                        rs.getString("name"),
                        rs.getLong("channel_id"),
                        rs.getObject("message_ref", Long.class),
                        PanelStyle.valueOf(rs.getString("style")),
                        rs.getString("placeholder"),
                        readPayload(rs.getString("payload")),
                        categories,
                        rs.getLong("created_by"),
                        rs.getTimestamp("updated_at").toInstant()))
                .findOne();
    }

    private static BlacklistEntry blacklistEntry(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new BlacklistEntry(TargetType.valueOf(rs.getString("target_type")), rs.getLong("target_id"), rs.getString("reason"),
                expires == null ? null : expires.toInstant(), rs.getLong("created_by"), rs.getTimestamp("created_at").toInstant());
    }

    private MessagePayload readPayload(String value) {
        try {
            return json.readValue(value, MessagePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored ticket payload is not valid JSON", e);
        }
    }

    private List<FieldOption> readOptions(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return json.readValue(value, OPTIONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored form options are not valid JSON", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value is not serializable", e);
        }
    }
}
