package de.notjan.bot.modules.tickets;

import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.MessagePayload;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class TicketConfig {

    public enum TargetType {
        ROLE,
        USER
    }

    public enum AccessKind {
        STAFF,
        TRANSCRIPTS
    }

    public enum PanelStyle {
        BUTTONS,
        SELECT
    }

    public enum ButtonColor {
        PRIMARY,
        SECONDARY,
        SUCCESS,
        DANGER
    }

    public enum RoleKind {
        SUPPORT,
        PING,
        REQUIRED
    }

    public enum FieldType {
        SHORT,
        PARAGRAPH,
        SELECT,
        RADIO,
        CHECKBOXES,
        CHECKBOX,
        USER,
        ROLE,
        CHANNEL,
        FILE;

        boolean usesOptions() {
            return this == SELECT || this == RADIO || this == CHECKBOXES;
        }

        boolean isText() {
            return this == SHORT || this == PARAGRAPH;
        }
    }

    public record Target(TargetType type, long id) {
    }

    public record Settings(long guildId, Long logChannelId, int closeDelaySeconds, int closeRequestHours, boolean creatorCanClose,
                           boolean claimLock, boolean transferToAnyone, boolean notesEnabled, boolean ratingsEnabled, boolean dmOnClose,
                           boolean creatorTranscript, Integer retentionDays, Set<Target> staff, Set<Target> transcriptViewers) {

        public Settings {
            staff = Set.copyOf(staff);
            transcriptViewers = Set.copyOf(transcriptViewers);
        }

        static Settings defaults(long guildId) {
            return new Settings(guildId, null, 5, 24, true, false, false, true, true, true, false, null, Set.of(), Set.of());
        }

        Set<Long> staffRoleIds() {
            return ids(staff, TargetType.ROLE);
        }

        Set<Long> staffUserIds() {
            return ids(staff, TargetType.USER);
        }

        private static Set<Long> ids(Set<Target> targets, TargetType type) {
            return targets.stream().filter(target -> target.type() == type).map(Target::id).collect(java.util.stream.Collectors.toSet());
        }
    }

    public record FieldOption(String label, String description, String emoji) {
    }

    public record FormField(long id, FieldType type, String label, String description, String placeholder, boolean required,
                            Integer minLength, Integer maxLength, Integer minValues, Integer maxValues, List<FieldOption> options) {

        public FormField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Category(long id, long guildId, String name, String emoji, String description, ButtonColor buttonStyle, Long parentId,
                           String channelPattern, MessagePayload welcome, String confirmText, String formTitle, int maxOpen,
                           int cooldownMinutes, boolean pingSupport, boolean includeStaff, boolean application, Long acceptRoleId, String acceptMessage,
                           String denyMessage, boolean closeOnDecision, int position, Set<Long> supportRoleIds, Set<Long> pingRoleIds,
                           Set<Long> requiredRoleIds, List<FormField> fields) {

        public Category {
            supportRoleIds = Set.copyOf(supportRoleIds);
            pingRoleIds = Set.copyOf(pingRoleIds);
            requiredRoleIds = Set.copyOf(requiredRoleIds);
            fields = List.copyOf(fields);
        }
    }

    public record Panel(long id, long guildId, String name, long channelId, BotMessage message, PanelStyle style, String placeholder,
                        MessagePayload payload, List<Long> categoryIds, long createdBy, Instant updatedAt) {

        public Panel {
            categoryIds = List.copyOf(categoryIds);
        }
    }

    public record BlacklistEntry(TargetType type, long targetId, String reason, Instant expiresAt, long createdBy, Instant createdAt) {
    }

    private TicketConfig() {
    }
}
