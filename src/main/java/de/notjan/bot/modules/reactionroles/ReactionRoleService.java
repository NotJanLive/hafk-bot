package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.message.BotMessageService.Sent;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Draft;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Mode;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Option;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Type;
import de.notjan.bot.modules.reactionroles.ReactionRoleRepository.PanelRow;
import de.notjan.bot.reset.ResettableData;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ReactionRoleService {

    private static final Logger LOG = LoggerFactory.getLogger(ReactionRoleService.class);

    static final String MODULE = "reaction-roles";
    static final String NAMESPACE = "rr";
    static final int MAX_OPTIONS = 25;
    static final int MAX_REACTIONS = 20;
    static final int MAX_LABEL = 80;
    static final int MAX_DESCRIPTION = 100;
    private static final int BUTTONS_PER_ROW = 5;
    private static final Set<String> BUTTON_STYLES = Set.of("PRIMARY", "SECONDARY", "SUCCESS", "DANGER");

    private final ReactionRoleRepository repository;
    private final BotMessageService messages;
    private final AuditLogService audit;
    private final Set<Long> reactionMessages = ConcurrentHashMap.newKeySet();

    public ReactionRoleService(ReactionRoleRepository repository, BotMessageService messages, AuditLogService audit) {
        this.repository = repository;
        this.messages = messages;
        this.audit = audit;
        reactionMessages.addAll(repository.reactionMessageIds());
    }

    public List<ReactionRolePanel> list(long guildId) {
        return repository.list(guildId).stream().flatMap(row -> toPanel(row).stream()).toList();
    }

    public ReactionRolePanel require(long guildId, long id) {
        return repository.find(guildId, id).flatMap(this::toPanel)
                .orElseThrow(() -> UserFacingException.notFound("Dieses Reaction-Role-Panel existiert nicht (mehr)."));
    }

    public Optional<ReactionRolePanel> findByDiscordMessage(long messageId) {
        return repository.findByDiscordMessage(messageId).flatMap(this::toPanel);
    }

    boolean isReactionPanel(long messageId) {
        return reactionMessages.contains(messageId);
    }

    public ReactionRolePanel create(Guild guild, long userId, Draft draft) {
        validate(guild, draft);
        String label = label(draft);
        Sent sent = messages.send(guild, draft.channelId(), MODULE, label, draft.payload(), components(draft), userId);
        long id = repository.insert(guild.getIdLong(), sent.record().id(), draft.type(), draft.mode(), draft.options());
        if (draft.type() == Type.REACTIONS) {
            reactionMessages.add(sent.message().getIdLong());
            draft.options().forEach(option -> sent.message().addReaction(Emoji.fromFormatted(option.emoji()))
                    .queue(null, error -> LOG.warn("Could not add reaction {} to panel {}", option.emoji(), id, error)));
        }
        audit.record(guild, userId, Source.DASHBOARD, "reaction-roles.create",
                "Reaction-Role-Panel „" + label + "“ in <#" + draft.channelId() + "> erstellt", Map.of("panelId", String.valueOf(id)));
        return require(guild.getIdLong(), id);
    }

    public ReactionRolePanel update(Guild guild, long userId, long id, Draft draft) {
        ReactionRolePanel panel = require(guild.getIdLong(), id);
        validate(guild, draft);
        String label = label(draft);
        messages.edit(guild, panel.message(), label, draft.payload(), components(draft));
        repository.update(id, draft.type(), draft.mode(), draft.options());

        long messageId = panel.message().messageId();
        if (panel.type() == Type.REACTIONS || draft.type() == Type.REACTIONS) {
            syncReactions(guild, panel.message(), draft);
        }
        if (draft.type() == Type.REACTIONS) {
            reactionMessages.add(messageId);
        } else {
            reactionMessages.remove(messageId);
        }
        audit.record(guild, userId, Source.DASHBOARD, "reaction-roles.update",
                "Reaction-Role-Panel „" + label + "“ bearbeitet", Map.of("panelId", String.valueOf(id)));
        return require(guild.getIdLong(), id);
    }

    public void delete(Guild guild, long userId, long id, boolean deleteMessage) {
        ReactionRolePanel panel = require(guild.getIdLong(), id);
        if (!deleteMessage) {
            detach(guild, panel.message());
        }
        messages.delete(guild, panel.message(), deleteMessage);
        reactionMessages.remove(panel.message().messageId());
        audit.record(guild, userId, Source.DASHBOARD, "reaction-roles.delete",
                "Reaction-Role-Panel „" + panel.message().label() + "“ gelöscht", Map.of("panelId", String.valueOf(id)));
    }

    public String toggle(Member member, ReactionRolePanel panel, long roleId) {
        if (panel.option(roleId).isEmpty()) {
            throw new UserFacingException("Diese Rolle gehört nicht mehr zu diesem Panel.");
        }
        Set<Long> memberRoles = roleIds(member);
        if (panel.mode() == Mode.VERIFY && memberRoles.contains(roleId)) {
            return "Du hast diese Rolle bereits.";
        }
        return apply(member, RoleSelection.toggle(panel.mode(), panel.roleIds(), memberRoles, roleId));
    }

    public String select(Member member, ReactionRolePanel panel, Set<Long> selected) {
        if (!panel.roleIds().containsAll(selected)) {
            throw new UserFacingException("Die Auswahl passt nicht mehr zu diesem Panel.");
        }
        return apply(member, RoleSelection.select(panel.mode(), panel.roleIds(), roleIds(member), selected));
    }

    public void reactionAdded(Member member, ReactionRolePanel panel, Emoji emoji) {
        optionFor(panel, emoji).ifPresent(option -> {
            var change = RoleSelection.reactionAdded(panel.mode(), panel.roleIds(), roleIds(member), option.roleId());
            apply(member, change);
            if (panel.mode() == Mode.UNIQUE) {
                removeOtherReactions(member, panel, change.remove());
            }
        });
    }

    public void reactionRemoved(Member member, ReactionRolePanel panel, Emoji emoji) {
        optionFor(panel, emoji).ifPresent(option ->
                apply(member, RoleSelection.reactionRemoved(panel.mode(), roleIds(member), option.roleId())));
    }

    private String apply(Member member, RoleSelection.Change change) {
        Guild guild = member.getGuild();
        List<Role> add = resolve(guild, change.add());
        List<Role> remove = resolve(guild, change.remove());
        if (add.isEmpty() && remove.isEmpty()) {
            return "Keine Änderung.";
        }
        try {
            guild.modifyMemberRoles(member, add, remove).complete();
        } catch (HierarchyException | InsufficientPermissionException | ErrorResponseException e) {
            throw new UserFacingException("Der Bot kann diese Rolle gerade nicht vergeben. Bitte wende dich an das Server-Team.");
        }
        List<String> lines = new ArrayList<>();
        if (!add.isEmpty()) {
            lines.add("✅ Du hast jetzt " + mentions(add) + ".");
        }
        if (!remove.isEmpty()) {
            lines.add("➖ Entfernt: " + mentions(remove) + ".");
        }
        return String.join("\n", lines);
    }

    static List<ActionRow> components(Draft draft) {
        return switch (draft.type()) {
            case BUTTONS -> {
                List<Button> buttons = draft.options().stream()
                        .map(option -> Button.of(ButtonStyle.valueOf(option.style() == null ? "SECONDARY" : option.style()),
                                ComponentId.of(NAMESPACE, "b", option.roleId()).toString(), blankToNull(option.label()), emoji(option)))
                        .toList();
                List<ActionRow> rows = new ArrayList<>();
                for (int i = 0; i < buttons.size(); i += BUTTONS_PER_ROW) {
                    rows.add(ActionRow.of(buttons.subList(i, Math.min(i + BUTTONS_PER_ROW, buttons.size()))));
                }
                yield rows;
            }
            case SELECT -> {
                List<SelectOption> options = draft.options().stream().map(option -> {
                    SelectOption selectOption = SelectOption.of(option.label(), String.valueOf(option.roleId()));
                    if (!isBlank(option.description())) {
                        selectOption = selectOption.withDescription(option.description());
                    }
                    Emoji emoji = emoji(option);
                    return emoji == null ? selectOption : selectOption.withEmoji(emoji);
                }).toList();
                StringSelectMenu menu = StringSelectMenu.create(ComponentId.of(NAMESPACE, "s").toString())
                        .setPlaceholder(draft.mode() == Mode.UNIQUE ? "Wähle eine Rolle" : "Wähle deine Rollen")
                        .setRequiredRange(0, draft.mode() == Mode.UNIQUE ? 1 : options.size())
                        .addOptions(options)
                        .build();
                yield List.of(ActionRow.of(menu));
            }
            case REACTIONS -> List.of();
        };
    }

    private void syncReactions(Guild guild, BotMessage message, Draft draft) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, message.channelId());
        if (channel == null) {
            return;
        }
        try {
            channel.clearReactionsById(message.messageId()).complete();
        } catch (InsufficientPermissionException | ErrorResponseException e) {
            LOG.debug("Could not clear reactions of {}", message.messageId(), e);
        }
        if (draft.type() == Type.REACTIONS) {
            draft.options().forEach(option -> channel.addReactionById(message.messageId(), Emoji.fromFormatted(option.emoji()))
                    .queue(null, error -> LOG.warn("Could not add reaction {}", option.emoji(), error)));
        }
    }

    private static void detach(Guild guild, BotMessage message) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, message.channelId());
        if (channel == null) {
            return;
        }
        channel.editMessageComponentsById(message.messageId(), List.<ActionRow>of()).queue(null, error -> { });
        try {
            channel.clearReactionsById(message.messageId()).queue(null, error -> { });
        } catch (InsufficientPermissionException e) {
            LOG.debug("Cannot clear reactions without Manage Messages");
        }
    }

    private static void removeOtherReactions(Member member, ReactionRolePanel panel, Set<Long> removedRoles) {
        GuildMessageChannel channel = member.getGuild().getChannelById(GuildMessageChannel.class, panel.message().channelId());
        if (channel == null) {
            return;
        }
        panel.options().stream()
                .filter(option -> removedRoles.contains(option.roleId()) && !isBlank(option.emoji()))
                .forEach(option -> {
                    try {
                        channel.removeReactionById(panel.message().messageId(), Emoji.fromFormatted(option.emoji()), member.getUser())
                                .queue(null, error -> { });
                    } catch (InsufficientPermissionException e) {
                        LOG.debug("Cannot remove reactions without Manage Messages");
                    }
                });
    }

    void validate(Guild guild, Draft draft) {
        List<String> errors = new ArrayList<>();
        if (draft.type() == null || draft.mode() == null) {
            throw new UserFacingException("Typ und Modus müssen angegeben werden.");
        }
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            errors.add("Der Bot braucht die Berechtigung „Rollen verwalten“.");
        }
        List<Option> options = draft.options() == null ? List.of() : draft.options();
        int max = draft.type() == Type.REACTIONS ? MAX_REACTIONS : MAX_OPTIONS;
        if (options.isEmpty()) {
            errors.add("Füge mindestens eine Rolle hinzu.");
        }
        if (options.size() > max) {
            errors.add("Es sind höchstens " + max + " Rollen pro Panel möglich.");
        }
        if (options.stream().map(Option::roleId).distinct().count() != options.size()) {
            errors.add("Jede Rolle darf nur einmal vorkommen.");
        }
        Set<String> emojis = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            String prefix = "Option " + (i + 1) + ": ";
            Role role = guild.getRoleById(option.roleId());
            if (role == null) {
                errors.add(prefix + "Die Rolle existiert nicht.");
            } else if (role.isPublicRole() || role.isManaged()) {
                errors.add(prefix + "Die Rolle " + role.getName() + " kann nicht vergeben werden.");
            } else if (!guild.getSelfMember().canInteract(role)) {
                errors.add(prefix + "Die Rolle " + role.getName() + " steht über der Rolle des Bots. "
                        + "Ziehe die Bot-Rolle in den Servereinstellungen darüber.");
            }
            if (draft.type() == Type.SELECT && isBlank(option.label())) {
                errors.add(prefix + "Für das Auswahlmenü braucht jede Rolle eine Beschriftung.");
            }
            if (draft.type() == Type.BUTTONS && isBlank(option.label()) && isBlank(option.emoji())) {
                errors.add(prefix + "Der Button braucht eine Beschriftung oder ein Emoji.");
            }
            if (option.label() != null && option.label().length() > MAX_LABEL) {
                errors.add(prefix + "Die Beschriftung darf höchstens " + MAX_LABEL + " Zeichen haben.");
            }
            if (option.description() != null && option.description().length() > MAX_DESCRIPTION) {
                errors.add(prefix + "Die Beschreibung darf höchstens " + MAX_DESCRIPTION + " Zeichen haben.");
            }
            if (draft.type() == Type.BUTTONS && option.style() != null && !BUTTON_STYLES.contains(option.style())) {
                errors.add(prefix + "Unbekannte Button-Farbe.");
            }
            if (draft.type() == Type.REACTIONS && isBlank(option.emoji())) {
                errors.add(prefix + "Für Reaktionen braucht jede Rolle ein Emoji.");
            }
            if (!isBlank(option.emoji())) {
                try {
                    Emoji.fromFormatted(option.emoji());
                    if (draft.type() == Type.REACTIONS && !emojis.add(option.emoji())) {
                        errors.add(prefix + "Jedes Emoji darf nur einmal vorkommen.");
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(prefix + "Das Emoji ist ungültig.");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
    }

    List<ResettableData> resettableData() {
        return List.of(ResettableData.of("reaction-roles.panels", "Reaction-Role-Panels",
                "Die Rollenzuordnungen. Behaltene Nachrichten reagieren danach nicht mehr auf Klicks.",
                repository::count,
                guildId -> {
                    messages.deleteRecords(guildId, MODULE);
                    reactionMessages.clear();
                    reactionMessages.addAll(repository.reactionMessageIds());
                }));
    }

    private Optional<ReactionRolePanel> toPanel(PanelRow row) {
        return messages.find(row.guildId(), row.messageRef())
                .map(message -> new ReactionRolePanel(row.id(), row.guildId(), message, row.type(), row.mode(), row.options()));
    }

    static Optional<Option> optionFor(ReactionRolePanel panel, Emoji reacted) {
        return panel.options().stream()
                .filter(option -> !isBlank(option.emoji()) && sameEmoji(Emoji.fromFormatted(option.emoji()), reacted))
                .findFirst();
    }

    private static boolean sameEmoji(EmojiUnion stored, Emoji reacted) {
        if (stored.getType() == Emoji.Type.CUSTOM) {
            return reacted.getType() == Emoji.Type.CUSTOM
                    && stored.asCustom().getIdLong() == ((net.dv8tion.jda.api.entities.emoji.CustomEmoji) reacted).getIdLong();
        }
        return reacted.getType() == Emoji.Type.UNICODE && Objects.equals(stored.getName(), reacted.getName());
    }

    private static Set<Long> roleIds(Member member) {
        return member.getRoles().stream().map(ISnowflake::getIdLong).collect(Collectors.toSet());
    }

    private static List<Role> resolve(Guild guild, Collection<Long> ids) {
        return ids.stream()
                .map(guild::getRoleById)
                .filter(Objects::nonNull)
                .filter(role -> guild.getSelfMember().canInteract(role))
                .toList();
    }

    private static String mentions(List<Role> roles) {
        return roles.stream().map(Role::getAsMention).collect(Collectors.joining(", "));
    }

    private static Emoji emoji(Option option) {
        return isBlank(option.emoji()) ? null : Emoji.fromFormatted(option.emoji());
    }

    private static String label(Draft draft) {
        String label = draft.label();
        if (isBlank(label)) {
            label = draft.payload() == null ? null : draft.payload().firstEmbed().title();
        }
        label = isBlank(label) ? "Reaction Roles" : label.strip();
        return label.length() > 100 ? label.substring(0, 99) + "…" : label;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
