package de.notjan.bot.modules.polls;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.modules.polls.Poll.Draft;
import de.notjan.bot.modules.polls.Poll.OptionDraft;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.modules.polls.PollService.PersonalView;
import de.notjan.bot.util.Brand;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class PollComponentHandler implements ComponentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PollComponentHandler.class);

    private final PollService service;

    PollComponentHandler(PollService service) {
        this.service = service;
    }

    @Override
    public String namespace() {
        return PollService.NAMESPACE;
    }

    record CreateSettings(long channelId, boolean anonymous, ResultVisibility visibility, int durationMinutes, int maxChoices,
                          boolean allowChange, boolean hostResults, long pingRoleId) {

        ComponentId toId() {
            return ComponentId.of(PollService.NAMESPACE, "create", channelId, flag(anonymous), visibility.ordinal(), durationMinutes,
                    maxChoices, flag(allowChange), flag(hostResults), pingRoleId);
        }

        static CreateSettings from(ComponentId id) {
            return new CreateSettings(id.longArg(0), "1".equals(id.arg(1)), ResultVisibility.values()[Integer.parseInt(id.arg(2))],
                    Integer.parseInt(id.arg(3)), Integer.parseInt(id.arg(4)), "1".equals(id.arg(5)), "1".equals(id.arg(6)),
                    id.longArg(7));
        }

        private static int flag(boolean value) {
            return value ? 1 : 0;
        }
    }

    static Modal createModal(CreateSettings settings) {
        return Modal.create(settings.toId().toString(), settings.anonymous() ? "Neue anonyme Umfrage" : "Neue Umfrage")
                .addComponents(
                        Label.of("Frage", TextInput.create("question", TextInputStyle.SHORT)
                                .setRequired(true)
                                .setMaxLength(PollService.MAX_QUESTION)
                                .setPlaceholder("Was sollen wir als Nächstes spielen?")
                                .build()),
                        Label.of("Antworten", "Eine Antwort pro Zeile, " + PollService.MIN_OPTIONS + " bis " + PollService.MAX_OPTIONS
                                        + ". Ein Emoji am Zeilenanfang wird übernommen.",
                                TextInput.create("answers", TextInputStyle.PARAGRAPH)
                                        .setRequired(true)
                                        .setMaxLength(PollService.MAX_OPTIONS * (PollService.MAX_LABEL + 4))
                                        .setPlaceholder("🚜 Landwirtschafts-Simulator\n🚚 Euro Truck Simulator\n⛏️ Minecraft")
                                        .build()),
                        Label.of("Beschreibung", TextInput.create("description", TextInputStyle.PARAGRAPH)
                                .setRequired(false)
                                .setMaxLength(PollService.MAX_DESCRIPTION)
                                .setPlaceholder("Optional, Markdown wird unterstützt")
                                .build()))
                .build();
    }

    @Override
    public void onButton(ButtonInteractionEvent event, ComponentId id) {
        defer(event);
        Member member = event.getMember();
        Poll poll = service.requireForInteraction(event.getGuild(), id.longArg(0));
        switch (id.action()) {
            case "v" -> showPersonalView(event, poll, service.vote(member, poll, id.longArg(1)));
            case "m" -> showPersonalView(event, poll, service.personalView(member, poll));
            case "w" -> Replies.success(event, service.withdraw(member, poll));
            case "e" -> {
                service.requireManager(member, poll);
                service.close(event.getGuild(), member.getIdLong(), Source.DISCORD, poll.id(), false);
                Replies.success(event, "🏁 Die Umfrage ist beendet. Das Ergebnis steht jetzt in der Nachricht.");
            }
            case "x" -> {
                service.requireManager(member, poll);
                send(event, "Willst du die Umfrage **" + poll.question() + "** wirklich abbrechen?\n\n"
                                + "Danach kann niemand mehr abstimmen und es wird kein Ergebnis veröffentlicht.",
                        List.of(ActionRow.of(Button.danger(ComponentId.of(PollService.NAMESPACE, "xc", poll.id()).toString(), "Ja, abbrechen")
                                .withEmoji(Emoji.fromUnicode("✖️")))));
            }
            case "xc" -> {
                service.requireManager(member, poll);
                service.close(event.getGuild(), member.getIdLong(), Source.DISCORD, poll.id(), true);
                Replies.success(event, "✖️ Die Umfrage wurde abgebrochen.");
            }
            default -> Replies.error(event, "Unbekannte Aktion.");
        }
    }

    @Override
    public void onStringSelect(StringSelectInteractionEvent event, ComponentId id) {
        defer(event);
        Member member = event.getMember();
        Poll poll = service.requireForInteraction(event.getGuild(), id.longArg(0));
        if (!"ext".equals(id.action())) {
            Replies.error(event, "Unbekannte Aktion.");
            return;
        }
        service.requireManager(member, poll);
        int minutes;
        try {
            minutes = Integer.parseInt(event.getValues().getFirst());
        } catch (RuntimeException e) {
            throw new UserFacingException("Ungültige Auswahl.");
        }
        Poll extended = service.extend(event.getGuild(), member.getIdLong(), Source.DISCORD, poll.id(), minutes);
        Replies.success(event, "⏳ Die Umfrage läuft jetzt bis <t:" + extended.endsAt().getEpochSecond() + ":f> (<t:"
                + extended.endsAt().getEpochSecond() + ":R>).");
    }

    @Override
    public void onModal(ModalInteractionEvent event, ComponentId id) {
        defer(event);
        if (!"create".equals(id.action())) {
            Replies.error(event, "Unbekannte Aktion.");
            return;
        }
        Member member = event.getMember();
        if (!service.canCreate(member)) {
            throw new UserFacingException("Du darfst auf diesem Server keine Umfragen erstellen.");
        }
        CreateSettings settings = CreateSettings.from(id);
        String answers = value(event, "answers");
        List<OptionDraft> options = answers == null ? List.of() : Arrays.stream(answers.split("\\R"))
                .filter(line -> !line.isBlank())
                .map(PollService::parseOption)
                .toList();
        Draft draft = new Draft(settings.channelId(), value(event, "question"), value(event, "description"), settings.anonymous(),
                settings.visibility(), settings.hostResults(), Math.min(settings.maxChoices(), Math.max(1, options.size())),
                settings.allowChange(), settings.pingRoleId() == 0 ? null : settings.pingRoleId(), settings.durationMinutes(), options,
                Set.of());
        Poll poll = service.create(event.getGuild(), member.getIdLong(), Source.DISCORD, draft);
        Replies.success(event, "📊 Umfrage gestartet: " + poll.message().jumpUrl()
                + "\nZum Verwalten: Rechtsklick auf die Umfrage → **Apps** → **Umfrage verwalten**.");
    }

    private static void showPersonalView(IReplyCallback event, Poll poll, PersonalView view) {
        List<ActionRow> rows = view.canWithdraw()
                ? List.of(ActionRow.of(Button.secondary(ComponentId.of(PollService.NAMESPACE, "w", poll.id()).toString(),
                "Stimme zurückziehen").withEmoji(Emoji.fromUnicode("↩️"))))
                : List.of();
        send(event, view.text(), rows);
    }

    static void showManageView(IReplyCallback event, PollService service, Member member, Poll poll) {
        String text = service.manageView(member, poll);
        List<ActionRow> rows = new ArrayList<>();
        if (poll.isOpen()) {
            rows.add(ActionRow.of(
                    Button.success(ComponentId.of(PollService.NAMESPACE, "e", poll.id()).toString(), "Jetzt beenden")
                            .withEmoji(Emoji.fromUnicode("🏁")),
                    Button.danger(ComponentId.of(PollService.NAMESPACE, "x", poll.id()).toString(), "Abbrechen")
                            .withEmoji(Emoji.fromUnicode("✖️"))));
            if (poll.endsAt() != null) {
                rows.add(ActionRow.of(StringSelectMenu.create(ComponentId.of(PollService.NAMESPACE, "ext", poll.id()).toString())
                        .setPlaceholder("⏳ Laufzeit verlängern")
                        .addOption("+1 Stunde", "60")
                        .addOption("+6 Stunden", "360")
                        .addOption("+1 Tag", "1440")
                        .addOption("+3 Tage", "4320")
                        .addOption("+1 Woche", "10080")
                        .build()));
            }
        }
        send(event, text, rows);
    }

    static void defer(IReplyCallback event) {
        event.deferReply(true).queue(null, error -> LOG.warn("Could not acknowledge poll interaction", error));
    }

    static void send(IReplyCallback event, String text, List<ActionRow> rows) {
        var embed = new EmbedBuilder().setColor(Brand.PRIMARY).setDescription(text).build();
        event.getHook().sendMessageEmbeds(embed).setComponents(rows).setEphemeral(true)
                .queue(null, error -> LOG.warn("Could not send poll reply", error));
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) {
            return null;
        }
        String value = mapping.getAsString().strip();
        return value.isEmpty() ? null : value;
    }
}
