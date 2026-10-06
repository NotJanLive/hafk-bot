package de.notjan.bot.modules.embeds;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.message.MessagePayload.Embed;
import de.notjan.bot.message.MessagePayload.Footer;
import de.notjan.bot.message.MessagePayload.Media;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EmbedModalHandler implements ComponentHandler {

    static final String NAMESPACE = "embed";
    private static final int MODAL_DESCRIPTION_LIMIT = 4000;

    private final EmbedService embeds;
    private final AccessService access;

    EmbedModalHandler(EmbedService embeds, AccessService access) {
        this.embeds = embeds;
        this.access = access;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    static Modal createModal(long channelId) {
        return modal(ComponentId.of(NAMESPACE, "create", channelId), "Neues Embed", Embed.EMPTY);
    }

    static Modal editModal(BotMessage message) {
        return modal(ComponentId.of(NAMESPACE, "edit", message.id()), "Embed bearbeiten", message.payload().firstEmbed());
    }

    private static Modal modal(ComponentId id, String title, Embed prefill) {
        return Modal.create(id.toString(), title)
                .addComponents(
                        Label.of("Titel", input("title", TextInputStyle.SHORT, 256, prefill.title(), null)),
                        Label.of("Beschreibung", input("description", TextInputStyle.PARAGRAPH, MODAL_DESCRIPTION_LIMIT,
                                prefill.description(), "Markdown wird unterstützt")),
                        Label.of("Farbe", "Hex-Code, z. B. #5B6CFF",
                                input("color", TextInputStyle.SHORT, 7, prefill.color() == null ? null : hex(prefill.color()), "#5B6CFF")),
                        Label.of("Bild-URL", input("image", TextInputStyle.SHORT, 2048,
                                prefill.image() == null ? null : prefill.image().url(), "https://…")),
                        Label.of("Footer", input("footer", TextInputStyle.SHORT, 2048,
                                prefill.footer() == null ? null : prefill.footer().text(), null)))
                .build();
    }

    private static TextInput input(String id, TextInputStyle style, int maxLength, String value, String placeholder) {
        var builder = TextInput.create(id, style).setRequired(false).setMaxLength(maxLength);
        if (value != null && !value.isBlank()) {
            builder.setValue(value.length() > maxLength ? value.substring(0, maxLength) : value);
        }
        if (placeholder != null) {
            builder.setPlaceholder(placeholder);
        }
        return builder.build();
    }

    @Override
    public void onModal(ModalInteractionEvent event, ComponentId id) {
        Guild guild = event.getGuild();
        long userId = event.getUser().getIdLong();
        EmbedCommand.requireAccess(access, guild, userId);

        String title = value(event, "title");
        String description = value(event, "description");
        Integer color = parseColor(value(event, "color"));
        String image = value(event, "image");
        String footer = value(event, "footer");

        event.deferReply(true).queue();
        switch (id.action()) {
            case "create" -> {
                Embed embed = new Embed(title, description, null, color, null, null,
                        footer == null ? null : new Footer(footer, null), null, image == null ? null : new Media(image), List.of());
                BotMessage sent = embeds.send(guild, userId, Source.DISCORD, id.longArg(0), null, new MessagePayload(null, List.of(embed)));
                Replies.success(event, "Embed gesendet: " + sent.jumpUrl() + "\nIm Dashboard kannst du alles weitere anpassen.");
            }
            case "edit" -> {
                BotMessage message = embeds.requireSent(guild.getIdLong(), id.longArg(0));
                Embed old = message.payload().firstEmbed();
                Embed updated = new Embed(title, description, old.url(), color, old.timestamp(), old.author(),
                        footer == null ? null : new Footer(footer, old.footer() == null ? null : old.footer().iconUrl()),
                        old.thumbnail(), image == null ? null : new Media(image), old.fields());
                List<Embed> all = new ArrayList<>(message.payload().embeds());
                if (all.isEmpty()) {
                    all.add(updated);
                } else {
                    all.set(0, updated);
                }
                BotMessage saved = embeds.edit(guild, userId, Source.DISCORD, message.id(), null,
                        new MessagePayload(message.payload().content(), all));
                Replies.success(event, "Embed aktualisiert: " + saved.jumpUrl());
            }
            default -> Replies.error(event, "Unbekannte Aktion.");
        }
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) {
            return null;
        }
        String value = mapping.getAsString().strip();
        return value.isEmpty() ? null : value;
    }

    static Integer parseColor(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new UserFacingException("Die Farbe muss ein Hex-Code wie #5B6CFF sein.");
        }
        return Integer.parseInt(hex, 16);
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }
}
