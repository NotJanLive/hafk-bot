package de.notjan.bot.modules.tickets;

import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketForms.Result;
import de.notjan.bot.util.Brand;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class TicketComponentHandler implements ComponentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TicketComponentHandler.class);

    private final TicketService service;
    private final TicketForms forms;

    TicketComponentHandler(TicketService service, TicketForms forms) {
        this.service = service;
        this.forms = forms;
    }

    @Override
    public String namespace() {
        return TicketRenderer.NAMESPACE;
    }

    @Override
    public void onStringSelect(StringSelectInteractionEvent event, ComponentId id) {
        if (!"open".equals(id.action())) {
            Replies.error(event, "Unbekannte Aktion.");
            return;
        }
        Message message = event.getMessage();
        try {
            Category category = service.requireCategory(event.getGuild(), Long.parseLong(event.getValues().getFirst()));
            startOpen(event, event.getMember(), category);
        } finally {
            message.editMessageComponents(message.getComponents()).queue(null, error -> LOG.debug("Could not reset ticket panel", error));
        }
    }

    @Override
    public void onButton(ButtonInteractionEvent event, ComponentId id) {
        switch (id.action()) {
            case "open" -> startOpen(event, event.getMember(), service.requireCategory(event.getGuild(), id.longArg(0)));
            case "confirm" -> {
                Category category = service.requireCategory(event.getGuild(), id.longArg(0));
                service.checkCanOpen(event.getMember(), category);
                proceed(event, event.getMember(), category);
            }
            case "next" -> {
                Category category = service.requireCategory(event.getGuild(), id.longArg(0));
                if (!forms.hasSession(event.getUser().getIdLong(), category.id())) {
                    throw new UserFacingException("Das Formular ist abgelaufen. Bitte starte es noch einmal über das Ticket-Panel.");
                }
                event.replyModal(TicketForms.modal(category, Integer.parseInt(id.arg(1)))).queue();
            }
            case "close" -> {
                Ticket ticket = service.requireOpen(event.getGuild(), id.longArg(0));
                service.requireCanClose(ticket, event.getMember());
                event.replyModal(reasonModal(TicketRenderer.id("closem", ticket.id()), "Ticket #" + ticket.number() + " schließen",
                        "Grund (optional)", "z. B. Problem gelöst")).queue();
            }
            case "claim" -> {
                defer(event);
                service.claim(service.requireOpen(event.getGuild(), id.longArg(0)), event.getMember());
                Replies.success(event, "🙋 Du bearbeitest jetzt dieses Ticket.");
            }
            case "unclaim" -> {
                defer(event);
                service.unclaim(service.requireOpen(event.getGuild(), id.longArg(0)), event.getMember());
                Replies.success(event, "↩️ Das Ticket ist wieder frei.");
            }
            case "notes" -> {
                defer(event);
                ThreadChannel thread = service.notes(service.requireOpen(event.getGuild(), id.longArg(0)), event.getMember());
                Replies.success(event, "📝 Team-Notizen: " + thread.getAsMention());
            }
            case "crc" -> {
                defer(event);
                Ticket ticket = service.requireOpen(event.getGuild(), id.longArg(0));
                Member member = event.getMember();
                if (member.getIdLong() != ticket.creatorId() && !service.isStaff(member, ticket)) {
                    throw new UserFacingException("Nur die Person, die das Ticket geöffnet hat, kann den Schließantrag bestätigen.");
                }
                service.close(event.getGuild(), ticket, member.getIdLong(),
                        ticket.closeRequestReason() == null ? "Schließantrag bestätigt" : ticket.closeRequestReason());
                Replies.success(event, "🔒 Das Ticket wird geschlossen.");
            }
            case "crd" -> {
                defer(event);
                service.cancelCloseRequest(service.requireOpen(event.getGuild(), id.longArg(0)), event.getMember());
                Replies.success(event, "✋ Das Ticket bleibt offen.");
            }
            case "accept", "deny" -> {
                Ticket ticket = service.requireOpen(event.getGuild(), id.longArg(0));
                service.requireStaff(event.getMember(), ticket);
                boolean accept = "accept".equals(id.action());
                event.replyModal(reasonModal(TicketRenderer.id("decide", ticket.id(), accept ? 1 : 0),
                        accept ? "Bewerbung annehmen" : "Bewerbung ablehnen", "Begründung (optional, geht per DM raus)",
                        accept ? "Willkommen im Team!" : "Leider passt es gerade nicht, weil …")).queue();
            }
            case "rate" -> {
                int stars = Integer.parseInt(id.arg(1));
                event.replyModal(Modal.create(TicketRenderer.id("ratem", id.longArg(0), stars), "Bewertung: " + "⭐".repeat(stars))
                        .addComponents(Label.of("Möchtest du noch etwas sagen?", TextInput.create("comment", TextInputStyle.PARAGRAPH)
                                .setRequired(false)
                                .setMaxLength(1000)
                                .setPlaceholder("Optional: Was lief gut, was können wir besser machen?")
                                .build()))
                        .build()).queue();
            }
            default -> Replies.error(event, "Unbekannte Aktion.");
        }
    }

    @Override
    public void onModal(ModalInteractionEvent event, ComponentId id) {
        switch (id.action()) {
            case "form" -> {
                defer(event);
                Category category = service.requireCategory(event.getGuild(), id.longArg(0));
                int page = Integer.parseInt(id.arg(1));
                Result result = forms.submit(event.getUser().getIdLong(), category, page, event);
                if (result == null) {
                    int pages = TicketForms.pages(category);
                    event.getHook().sendMessageEmbeds(TicketRenderer.notice("✅ Seite " + (page + 1) + " von " + pages
                                    + " gespeichert. Weiter geht's mit dem nächsten Teil."))
                            .setComponents(ActionRow.of(Button.primary(TicketRenderer.id("next", category.id(), page + 1),
                                    "Weiter zu Seite " + (page + 2) + "/" + pages).withEmoji(Emoji.fromUnicode("➡️"))))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
                service.checkCanOpen(event.getMember(), category);
                Ticket ticket = service.open(event.getGuild(), event.getMember(), event.getMember(), category, result.answers(), result.files());
                Replies.success(event, "🎫 Dein Ticket wurde erstellt: <#" + ticket.channelId() + ">");
            }
            case "closem" -> {
                defer(event);
                Ticket ticket = service.requireOpen(event.getGuild(), id.longArg(0));
                service.requireCanClose(ticket, event.getMember());
                service.close(event.getGuild(), ticket, event.getMember().getIdLong(), value(event, "reason"));
                Replies.success(event, "🔒 Das Ticket wird geschlossen.");
            }
            case "decide" -> {
                defer(event);
                Ticket ticket = service.requireOpen(event.getGuild(), id.longArg(0));
                boolean accept = "1".equals(id.arg(1));
                service.decide(ticket, event.getMember(), accept, value(event, "reason"));
                Replies.success(event, accept ? "✅ Bewerbung angenommen." : "❌ Bewerbung abgelehnt.");
            }
            case "ratem" -> {
                event.deferEdit().queue(null, error -> LOG.debug("Could not acknowledge rating", error));
                long ticketId = id.longArg(0);
                int stars = Integer.parseInt(id.arg(1));
                boolean saved = service.rate(ticketId, event.getUser().getIdLong(), stars, value(event, "comment"));
                Ticket ticket = service.find(ticketId).orElseThrow();
                String link = service.settings(ticket.guildId()).creatorTranscript() ? service.transcriptUrl(ticket) : null;
                event.getHook().editOriginalComponents(TicketRenderer.summaryButtons(ticket, link, false))
                        .queue(null, error -> LOG.debug("Could not remove rating buttons", error));
                event.getHook().sendMessageEmbeds(new EmbedBuilder().setColor(Brand.PRIMARY)
                                .setDescription(saved ? "Danke für deine Bewertung " + "⭐".repeat(stars) + "!" : "Du hast dieses Ticket bereits bewertet.")
                                .build())
                        .queue(null, error -> LOG.debug("Could not thank for rating", error));
            }
            default -> Replies.error(event, "Unbekannte Aktion.");
        }
    }

    private <T extends IReplyCallback & IModalCallback> void startOpen(T event, Member member, Category category) {
        service.checkCanOpen(member, category);
        if (category.confirmText() != null) {
            event.replyEmbeds(new EmbedBuilder().setColor(Brand.PRIMARY).setTitle(TicketRenderer.truncate(category.name(), 256))
                            .setDescription(category.confirmText()).build())
                    .setComponents(ActionRow.of(Button.success(TicketRenderer.id("confirm", category.id()), "Ticket öffnen")
                            .withEmoji(Emoji.fromUnicode("🎫"))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        proceed(event, member, category);
    }

    private <T extends IReplyCallback & IModalCallback> void proceed(T event, Member member, Category category) {
        if (!category.fields().isEmpty()) {
            event.replyModal(TicketForms.modal(category, 0)).queue();
            return;
        }
        defer(event);
        Ticket ticket = service.open(event.getGuild(), member, member, category, List.of(), List.of());
        Replies.success(event, "🎫 Dein Ticket wurde erstellt: <#" + ticket.channelId() + ">");
    }

    private static Modal reasonModal(String id, String title, String label, String placeholder) {
        return Modal.create(id, TicketRenderer.truncate(title, 45))
                .addComponents(Label.of(label, TextInput.create("reason", TextInputStyle.PARAGRAPH)
                        .setRequired(false)
                        .setMaxLength(1000)
                        .setPlaceholder(placeholder)
                        .build()))
                .build();
    }

    private static void defer(IReplyCallback event) {
        event.deferReply(true).queue(null, error -> LOG.warn("Could not acknowledge ticket interaction", error));
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
