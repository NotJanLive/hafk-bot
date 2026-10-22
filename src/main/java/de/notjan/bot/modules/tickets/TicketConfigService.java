package de.notjan.bot.modules.tickets;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.message.BotMessageService.Sent;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.message.PayloadValidator;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.FieldOption;
import de.notjan.bot.modules.tickets.TicketConfig.FieldType;
import de.notjan.bot.modules.tickets.TicketConfig.FormField;
import de.notjan.bot.modules.tickets.TicketConfig.Panel;
import de.notjan.bot.modules.tickets.TicketConfig.PanelStyle;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import de.notjan.bot.modules.tickets.TicketConfigRepository.PanelRow;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TicketConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(TicketConfigService.class);

    static final int MAX_CATEGORIES = 25;
    static final int MAX_PANELS = 25;
    static final int MAX_ACCESS_TARGETS = 50;
    static final int MAX_ROLES = 25;

    record PanelDraft(String name, long channelId, PanelStyle style, String placeholder, MessagePayload payload, List<Long> categoryIds) {
    }

    private final TicketConfigRepository repository;
    private final BotMessageService messages;
    private final AuditLogService audit;

    public TicketConfigService(TicketConfigRepository repository, BotMessageService messages, AuditLogService audit) {
        this.repository = repository;
        this.messages = messages;
        this.audit = audit;
    }

    Settings settings(long guildId) {
        return repository.settings(guildId);
    }

    Settings updateSettings(Guild guild, long userId, Settings draft) {
        List<String> errors = new ArrayList<>();
        if (draft.logChannelId() != null && guild.getTextChannelById(draft.logChannelId()) == null) {
            errors.add("Der Log-Kanal existiert nicht oder ist kein Textkanal.");
        }
        if (draft.closeDelaySeconds() < 0 || draft.closeDelaySeconds() > 300) {
            errors.add("Die Verzögerung beim Schließen muss zwischen 0 und 300 Sekunden liegen.");
        }
        if (draft.closeRequestHours() < 1 || draft.closeRequestHours() > TicketService.MAX_CLOSE_REQUEST_HOURS) {
            errors.add("Die Standardzeit für Schließanträge muss zwischen 1 und " + TicketService.MAX_CLOSE_REQUEST_HOURS + " Stunden liegen.");
        }
        if (draft.retentionDays() != null && (draft.retentionDays() < 1 || draft.retentionDays() > 3650)) {
            errors.add("Transkripte können zwischen 1 und 3650 Tagen aufbewahrt werden.");
        }
        validateTargets(guild, draft.staff(), "Support-Team", errors);
        validateTargets(guild, draft.transcriptViewers(), "Transkript-Zugriff", errors);
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
        repository.saveSettings(new Settings(guild.getIdLong(), draft.logChannelId(), draft.closeDelaySeconds(), draft.closeRequestHours(),
                draft.creatorCanClose(), draft.claimLock(), draft.transferToAnyone(), draft.notesEnabled(), draft.ratingsEnabled(),
                draft.dmOnClose(), draft.creatorTranscript(), draft.retentionDays(), draft.staff(), draft.transcriptViewers()));
        audit.record(guild, userId, Source.DASHBOARD, "tickets.settings", "Ticket-Einstellungen geändert", Map.of());
        syncLogChannel(guild);
        return repository.settings(guild.getIdLong());
    }

    List<Category> categories(long guildId) {
        return repository.categories(guildId);
    }

    Category category(Guild guild, long id) {
        return repository.category(id).filter(category -> category.guildId() == guild.getIdLong())
                .orElseThrow(() -> UserFacingException.notFound("Diese Kategorie existiert nicht (mehr)."));
    }

    Category saveCategory(Guild guild, long userId, Category draft) {
        boolean create = draft.id() == 0;
        if (!create) {
            category(guild, draft.id());
        } else if (repository.categories(guild.getIdLong()).size() >= MAX_CATEGORIES) {
            throw new UserFacingException("Es sind höchstens " + MAX_CATEGORIES + " Ticket-Kategorien möglich.");
        }
        Category cleaned = clean(guild, draft);
        long id = repository.saveCategory(cleaned);
        syncLogChannel(guild);
        audit.record(guild, userId, Source.DASHBOARD, create ? "tickets.category.create" : "tickets.category.update",
                "Ticket-Kategorie „" + cleaned.name() + "“ " + (create ? "erstellt" : "geändert"), Map.of("categoryId", String.valueOf(id)));
        if (!create) {
            refreshPanels(guild, repository.panelsWithCategory(id));
        }
        return category(guild, id);
    }

    void deleteCategory(Guild guild, long userId, long id) {
        Category category = category(guild, id);
        List<Long> panels = repository.panelsWithCategory(id);
        repository.deleteCategory(id);
        audit.record(guild, userId, Source.DASHBOARD, "tickets.category.delete", "Ticket-Kategorie „" + category.name() + "“ gelöscht",
                Map.of("categoryId", String.valueOf(id)));
        refreshPanels(guild, panels);
    }

    List<Panel> panels(long guildId) {
        return repository.panels(guildId).stream().map(this::toPanel).toList();
    }

    Panel panel(Guild guild, long id) {
        return repository.panel(id).filter(row -> row.guildId() == guild.getIdLong()).map(this::toPanel)
                .orElseThrow(() -> UserFacingException.notFound("Dieses Panel existiert nicht (mehr)."));
    }

    Panel savePanel(Guild guild, long userId, long id, PanelDraft draft) {
        boolean create = id == 0;
        Panel existing = create ? null : panel(guild, id);
        if (create && repository.panels(guild.getIdLong()).size() >= MAX_PANELS) {
            throw new UserFacingException("Es sind höchstens " + MAX_PANELS + " Panels möglich.");
        }
        List<String> errors = new ArrayList<>();
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            errors.add("Der Name muss 1 bis 100 Zeichen haben.");
        }
        if (draft.style() == null) {
            errors.add("Wähle aus, ob das Panel Buttons oder ein Auswahlmenü nutzt.");
        }
        if (draft.placeholder() != null && draft.placeholder().length() > 150) {
            errors.add("Der Platzhalter darf höchstens 150 Zeichen haben.");
        }
        errors.addAll(PayloadValidator.validate(draft.payload()));
        List<Long> categoryIds = draft.categoryIds() == null ? List.of() : draft.categoryIds().stream().distinct().toList();
        if (categoryIds.isEmpty()) {
            errors.add("Wähle mindestens eine Kategorie für das Panel aus.");
        }
        if (categoryIds.size() > MAX_CATEGORIES) {
            errors.add("Ein Panel kann höchstens " + MAX_CATEGORIES + " Kategorien anzeigen.");
        }
        List<Category> categories = new ArrayList<>();
        for (long categoryId : categoryIds) {
            repository.category(categoryId).filter(category -> category.guildId() == guild.getIdLong())
                    .ifPresentOrElse(categories::add, () -> errors.add("Die Kategorie " + categoryId + " existiert nicht."));
        }
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
        String placeholder = draft.placeholder() == null || draft.placeholder().isBlank() ? null : draft.placeholder().strip();
        BotMessageService.writableChannel(guild, draft.channelId());
        long panelId = repository.savePanel(id, guild.getIdLong(), name, draft.channelId(), draft.style(), placeholder, draft.payload(),
                categoryIds, userId);
        var components = TicketRenderer.panelComponents(draft.style(), placeholder, categories);
        try {
            if (existing != null && existing.message() != null && existing.message().channelId() == draft.channelId()) {
                messages.edit(guild, existing.message(), TicketRenderer.truncate(name, 100), draft.payload(), components);
            } else {
                if (existing != null && existing.message() != null) {
                    messages.delete(guild, existing.message(), true);
                }
                Sent sent = messages.send(guild, draft.channelId(), TicketService.MODULE, TicketRenderer.truncate(name, 100), draft.payload(),
                        components, userId);
                repository.setPanelMessage(panelId, sent.record().id());
            }
        } catch (UserFacingException e) {
            if (create) {
                repository.deletePanel(panelId);
            }
            throw e;
        }
        audit.record(guild, userId, Source.DASHBOARD, create ? "tickets.panel.create" : "tickets.panel.update",
                "Ticket-Panel „" + name + "“ " + (create ? "in <#" + draft.channelId() + "> gesendet" : "aktualisiert"),
                Map.of("panelId", String.valueOf(panelId)));
        return panel(guild, panelId);
    }

    void deletePanel(Guild guild, long userId, long id, boolean deleteMessage) {
        Panel panel = panel(guild, id);
        if (panel.message() != null) {
            messages.delete(guild, panel.message(), deleteMessage);
        }
        repository.deletePanel(id);
        audit.record(guild, userId, Source.DASHBOARD, "tickets.panel.delete", "Ticket-Panel „" + panel.name() + "“ gelöscht",
                Map.of("panelId", String.valueOf(id)));
    }

    void refreshPanels(Guild guild, List<Long> panelIds) {
        for (long panelId : panelIds) {
            repository.panel(panelId).map(this::toPanel).ifPresent(panel -> {
                if (panel.message() == null) {
                    return;
                }
                List<Category> categories = panel.categoryIds().stream()
                        .map(repository::category)
                        .flatMap(Optional::stream)
                        .toList();
                try {
                    messages.edit(guild, panel.message(), TicketRenderer.truncate(panel.name(), 100), panel.payload(),
                            TicketRenderer.panelComponents(panel.style(), panel.placeholder(), categories));
                } catch (UserFacingException e) {
                    LOG.debug("Could not refresh ticket panel {}: {}", panelId, e.getMessage());
                }
            });
        }
    }

    long countConfig(long guildId) {
        return repository.countConfig(guildId);
    }

    void deleteConfig(long guildId) {
        repository.deleteConfig(guildId);
        messages.deleteRecords(guildId, TicketService.MODULE);
    }

    long countBlacklist(long guildId) {
        return repository.countBlacklist(guildId);
    }

    void deleteBlacklist(long guildId) {
        repository.deleteBlacklist(guildId);
    }

    void syncLogChannel(Guild guild) {
        Settings settings = repository.settings(guild.getIdLong());
        TextChannel channel = settings.logChannelId() == null ? null : guild.getTextChannelById(settings.logChannelId());
        if (channel == null || !guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            return;
        }
        PermissionOverride everyone = channel.getPermissionOverride(guild.getPublicRole());
        if (everyone == null || !everyone.getDenied().contains(Permission.VIEW_CHANNEL)) {
            return;
        }
        Set<Long> roleIds = new HashSet<>(settings.staffRoleIds());
        repository.categories(guild.getIdLong()).forEach(category -> roleIds.addAll(category.supportRoleIds()));
        var manager = channel.getManager();
        boolean changed = false;
        for (long roleId : roleIds) {
            Role role = guild.getRoleById(roleId);
            if (role == null || role.isPublicRole()) {
                continue;
            }
            PermissionOverride current = channel.getPermissionOverride(role);
            if (current != null && current.getAllowed().contains(Permission.VIEW_CHANNEL)) {
                continue;
            }
            EnumSet<Permission> allow = current == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(current.getAllowed());
            EnumSet<Permission> deny = current == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(current.getDenied());
            allow.add(Permission.VIEW_CHANNEL);
            allow.add(Permission.MESSAGE_HISTORY);
            deny.removeAll(allow);
            manager = manager.putRolePermissionOverride(roleId, allow, deny);
            changed = true;
        }
        if (changed) {
            manager.reason("Ticket-Team hat Zugriff auf den Log-Kanal")
                    .queue(null, error -> LOG.warn("Could not update log channel permissions in guild {}", guild.getId(), error));
        }
    }

    private Panel toPanel(PanelRow row) {
        BotMessage message = row.messageRef() == null ? null : messages.find(row.guildId(), row.messageRef()).orElse(null);
        return new Panel(row.id(), row.guildId(), row.name(), row.channelId(), message, row.style(), row.placeholder(), row.payload(),
                row.categoryIds(), row.createdBy(), row.updatedAt());
    }

    private Category clean(Guild guild, Category draft) {
        List<String> errors = new ArrayList<>();
        String name = strip(draft.name());
        if (name == null || name.length() > 80) {
            errors.add("Der Name muss 1 bis 80 Zeichen haben.");
        }
        String emoji = strip(draft.emoji());
        if (emoji != null && TicketRenderer.emoji(emoji) == null) {
            errors.add("Das Emoji ist ungültig.");
        }
        String description = strip(draft.description());
        if (description != null && description.length() > 100) {
            errors.add("Die Beschreibung darf höchstens 100 Zeichen haben.");
        }
        if (draft.buttonStyle() == null) {
            errors.add("Die Button-Farbe fehlt.");
        }
        if (draft.parentId() != null && guild.getCategoryById(draft.parentId()) == null) {
            errors.add("Die Discord-Kategorie für Ticket-Kanäle existiert nicht.");
        }
        String pattern = strip(draft.channelPattern());
        if (pattern == null) {
            pattern = "{category}-{number}";
        } else if (pattern.length() > 100) {
            errors.add("Das Muster für Kanalnamen darf höchstens 100 Zeichen haben.");
        }
        if (draft.welcome() != null) {
            errors.addAll(PayloadValidator.validate(draft.welcome()).stream().map(error -> "Begrüßung: " + error).toList());
        }
        String confirm = strip(draft.confirmText());
        if (confirm != null && confirm.length() > 2000) {
            errors.add("Der Hinweis vor dem Öffnen darf höchstens 2000 Zeichen haben.");
        }
        String formTitle = strip(draft.formTitle());
        if (formTitle != null && formTitle.length() > 40) {
            errors.add("Der Formulartitel darf höchstens 40 Zeichen haben.");
        }
        if (draft.maxOpen() < 0 || draft.maxOpen() > 10) {
            errors.add("Offene Tickets pro Person: 0 (unbegrenzt) bis 10.");
        }
        if (draft.cooldownMinutes() < 0 || draft.cooldownMinutes() > 60 * 24 * 7) {
            errors.add("Die Wartezeit zwischen Tickets darf höchstens 7 Tage betragen.");
        }
        validateRoles(guild, draft.supportRoleIds(), "Support-Rollen", errors);
        if (!draft.includeStaff() && draft.supportRoleIds().isEmpty()) {
            errors.add("Ohne das globale Support-Team braucht die Kategorie mindestens eine eigene Support-Rolle.");
        }
        validateRoles(guild, draft.pingRoleIds(), "Ping-Rollen", errors);
        validateRoles(guild, draft.requiredRoleIds(), "Benötigte Rollen", errors);
        if (draft.acceptRoleId() != null) {
            Role role = guild.getRoleById(draft.acceptRoleId());
            if (role == null) {
                errors.add("Die Rolle für angenommene Bewerbungen existiert nicht.");
            } else if (role.isManaged() || role.isPublicRole() || !guild.getSelfMember().canInteract(role)) {
                errors.add("Der Bot kann die Rolle @" + role.getName() + " nicht vergeben. Sie muss unter der Bot-Rolle stehen.");
            }
        }
        if (strip(draft.acceptMessage()) != null && draft.acceptMessage().strip().length() > 1000
                || strip(draft.denyMessage()) != null && draft.denyMessage().strip().length() > 1000) {
            errors.add("Die Nachrichten für Annahme und Ablehnung dürfen höchstens 1000 Zeichen haben.");
        }
        List<FormField> fields = draft.fields() == null ? List.of() : draft.fields();
        if (fields.size() > TicketForms.MAX_FIELDS) {
            errors.add("Ein Formular kann höchstens " + TicketForms.MAX_FIELDS + " Fragen haben.");
        }
        List<FormField> cleanedFields = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            cleanedFields.add(cleanField(fields.get(i), "Frage " + (i + 1) + ": ", errors));
        }
        if (draft.application() && cleanedFields.isEmpty()) {
            errors.add("Eine Bewerbung braucht mindestens eine Frage im Formular.");
        }
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
        return new Category(draft.id(), guild.getIdLong(), name, emoji, description, draft.buttonStyle(), draft.parentId(), pattern,
                draft.welcome(), confirm, formTitle, draft.maxOpen(), draft.cooldownMinutes(), draft.pingSupport(), draft.includeStaff(), draft.application(),
                draft.application() ? draft.acceptRoleId() : null, strip(draft.acceptMessage()), strip(draft.denyMessage()),
                draft.application() && draft.closeOnDecision(), draft.position(), draft.supportRoleIds(), draft.pingRoleIds(),
                draft.requiredRoleIds(), cleanedFields);
    }

    private static FormField cleanField(FormField field, String prefix, List<String> errors) {
        if (field.type() == null) {
            errors.add(prefix + "Der Typ fehlt.");
            return field;
        }
        String label = strip(field.label());
        if (label == null || label.length() > 45) {
            errors.add(prefix + "Die Frage muss 1 bis 45 Zeichen haben.");
        }
        String description = strip(field.description());
        if (description != null && description.length() > 100) {
            errors.add(prefix + "Die Beschreibung darf höchstens 100 Zeichen haben.");
        }
        String placeholder = strip(field.placeholder());
        if (placeholder != null && placeholder.length() > 100) {
            errors.add(prefix + "Der Platzhalter darf höchstens 100 Zeichen haben.");
        }
        Integer minLength = null;
        Integer maxLength = null;
        if (field.type().isText()) {
            minLength = field.minLength() == null || field.minLength() <= 0 ? null : field.minLength();
            maxLength = field.maxLength() == null || field.maxLength() <= 0 ? null : field.maxLength();
            if (minLength != null && minLength > 4000 || maxLength != null && maxLength > 4000) {
                errors.add(prefix + "Antworten können höchstens 4000 Zeichen lang sein.");
            }
            if (minLength != null && maxLength != null && minLength > maxLength) {
                errors.add(prefix + "Die Mindestlänge ist größer als die Maximallänge.");
            }
        }
        List<FieldOption> options = new ArrayList<>();
        if (field.type().usesOptions()) {
            int min = field.type() == FieldType.RADIO ? 2 : 1;
            int max = field.type() == FieldType.SELECT ? 25 : 10;
            Set<String> labels = new HashSet<>();
            for (FieldOption option : field.options()) {
                String optionLabel = strip(option.label());
                if (optionLabel == null || optionLabel.length() > 100) {
                    errors.add(prefix + "Jede Auswahl braucht einen Text mit höchstens 100 Zeichen.");
                    continue;
                }
                if (!labels.add(optionLabel.toLowerCase())) {
                    errors.add(prefix + "Die Auswahl „" + optionLabel + "“ gibt es doppelt.");
                }
                String optionDescription = strip(option.description());
                if (optionDescription != null && optionDescription.length() > 100) {
                    errors.add(prefix + "Beschreibungen von Auswahlmöglichkeiten dürfen höchstens 100 Zeichen haben.");
                }
                String optionEmoji = strip(option.emoji());
                if (optionEmoji != null && TicketRenderer.emoji(optionEmoji) == null) {
                    errors.add(prefix + "Ein Emoji einer Auswahl ist ungültig.");
                }
                options.add(new FieldOption(optionLabel, optionDescription, field.type() == FieldType.SELECT ? optionEmoji : null));
            }
            if (options.size() < min || options.size() > max) {
                errors.add(prefix + "Hier sind " + min + " bis " + max + " Auswahlmöglichkeiten nötig.");
            }
        }
        Integer minValues = null;
        Integer maxValues = null;
        if (field.type() == FieldType.SELECT || field.type() == FieldType.CHECKBOXES || field.type() == FieldType.USER
                || field.type() == FieldType.ROLE || field.type() == FieldType.CHANNEL || field.type() == FieldType.FILE) {
            int limit = switch (field.type()) {
                case SELECT, CHECKBOXES -> Math.max(1, options.size());
                case FILE -> 10;
                default -> 25;
            };
            maxValues = field.maxValues() == null ? 1 : field.maxValues();
            minValues = field.minValues() == null ? (field.required() ? 1 : 0) : field.minValues();
            if (maxValues < 1 || maxValues > limit) {
                errors.add(prefix + "Es können 1 bis " + limit + " Antworten gewählt werden.");
            }
            if (minValues < 0 || minValues > maxValues) {
                errors.add(prefix + "Die Mindestanzahl muss zwischen 0 und der Höchstanzahl liegen.");
            }
        }
        return new FormField(0, field.type(), label, description, placeholder, field.required(), minLength, maxLength, minValues, maxValues,
                options);
    }

    private static void validateRoles(Guild guild, Set<Long> roleIds, String label, List<String> errors) {
        if (roleIds.size() > MAX_ROLES) {
            errors.add(label + ": höchstens " + MAX_ROLES + " Rollen.");
        }
        roleIds.stream().filter(id -> guild.getRoleById(id) == null).forEach(id -> errors.add(label + ": Die Rolle " + id + " existiert nicht."));
    }

    private static void validateTargets(Guild guild, Set<Target> targets, String label, List<String> errors) {
        if (targets.size() > MAX_ACCESS_TARGETS) {
            errors.add(label + ": höchstens " + MAX_ACCESS_TARGETS + " Einträge.");
        }
        targets.stream()
                .filter(target -> target.type() == TargetType.ROLE && guild.getRoleById(target.id()) == null)
                .forEach(target -> errors.add(label + ": Die Rolle " + target.id() + " existiert nicht."));
        targets.stream()
                .filter(target -> target.type() == TargetType.ROLE && target.id() == guild.getIdLong())
                .findAny()
                .ifPresent(target -> errors.add(label + ": @everyone ist nicht erlaubt."));
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
