package de.notjan.bot.modules.tickets;

import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.message.MessagePayload.Embed;
import de.notjan.bot.message.MessagePayload.Field;
import de.notjan.bot.message.MessagePayload.Footer;
import de.notjan.bot.message.PayloadRenderer;
import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.Ticket.ApplicationState;
import de.notjan.bot.modules.tickets.TicketConfig.ButtonColor;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.PanelStyle;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

final class TicketRenderer {

    static final String NAMESPACE = "tk";
    static final Color SUCCESS = new Color(0x3BA55D);
    private static final int BUTTONS_PER_ROW = 5;

    private TicketRenderer() {
    }

    static String id(String action, Object... args) {
        return ComponentId.of(NAMESPACE, action, args).toString();
    }

    static List<ActionRow> panelComponents(PanelStyle style, String placeholder, List<Category> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        if (style == PanelStyle.SELECT) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(id("open"))
                    .setPlaceholder(placeholder == null || placeholder.isBlank() ? "Wähle dein Anliegen aus …" : placeholder)
                    .setRequiredRange(1, 1);
            for (Category category : categories) {
                Emoji emoji = emoji(category.emoji());
                menu.addOption(truncate(category.name(), 100), String.valueOf(category.id()),
                        category.description() == null ? null : truncate(category.description(), 100), emoji);
            }
            return List.of(ActionRow.of(menu.build()));
        }
        List<ActionRow> rows = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (Category category : categories) {
            Button button = Button.of(style(category.buttonStyle()), id("open", category.id()), truncate(category.name(), 80));
            Emoji emoji = emoji(category.emoji());
            row.add(emoji == null ? button : button.withEmoji(emoji));
            if (row.size() == BUTTONS_PER_ROW) {
                rows.add(ActionRow.of(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(ActionRow.of(row));
        }
        return rows;
    }

    static ActionRow controls(Ticket ticket, Settings settings) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.danger(id("close", ticket.id()), "Schließen").withEmoji(Emoji.fromUnicode("🔒")));
        buttons.add(ticket.claimedBy() == null
                ? Button.success(id("claim", ticket.id()), "Übernehmen").withEmoji(Emoji.fromUnicode("🙋"))
                : Button.secondary(id("unclaim", ticket.id()), "Freigeben").withEmoji(Emoji.fromUnicode("↩️")));
        if (settings.notesEnabled()) {
            buttons.add(Button.secondary(id("notes", ticket.id()), "Team-Notizen").withEmoji(Emoji.fromUnicode("📝")));
        }
        return ActionRow.of(buttons);
    }

    static MessageCreateData welcome(Guild guild, Ticket ticket, Category category, Settings settings, Set<Long> pingRoles) {
        UnaryOperator<String> placeholders = placeholders(guild, ticket);
        MessagePayload payload = category != null && category.welcome() != null ? category.welcome() : defaultWelcome();
        MessagePayload filled = fill(payload, placeholders);
        StringBuilder mentions = new StringBuilder("<@").append(ticket.creatorId()).append(">");
        pingRoles.forEach(roleId -> mentions.append(" <@&").append(roleId).append(">"));
        String content = filled.content() == null || filled.content().isBlank()
                ? mentions.toString()
                : mentions + "\n" + filled.content();
        MessagePayload withMentions = new MessagePayload(truncate(content, 2000), filled.embeds());
        return new MessageCreateBuilder()
                .applyData(PayloadRenderer.create(withMentions, List.of(controls(ticket, settings))))
                .setAllowedMentions(List.of(net.dv8tion.jda.api.entities.Message.MentionType.USER,
                        net.dv8tion.jda.api.entities.Message.MentionType.ROLE))
                .mentionUsers(ticket.creatorId())
                .mentionRoles(pingRoles.stream().map(String::valueOf).toList())
                .build();
    }

    static MessagePayload defaultWelcome() {
        return new MessagePayload(null, List.of(new Embed("{category} · Ticket #{number}",
                "Hallo {user}, danke für deine Anfrage!\n\nDas Team meldet sich so schnell wie möglich bei dir. "
                        + "Beschreibe dein Anliegen bitte schon einmal so genau wie möglich.",
                null, Brand.PRIMARY.getRGB() & 0xFFFFFF, null, null,
                new Footer("Mit 🔒 Schließen beendest du das Ticket.", null), null, null, List.of())));
    }

    static MessageEmbed answers(Ticket ticket, List<Answer> answers, ApplicationState state) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(state == ApplicationState.ACCEPTED ? SUCCESS : state == ApplicationState.DENIED ? Brand.DANGER : Brand.PRIMARY)
                .setTitle(state == null ? "📋 Angaben zum Ticket" : "📋 Bewerbung von " + truncate(ticket.creatorName(), 200));
        for (Answer answer : answers.stream().limit(25).toList()) {
            embed.addField(truncate(answer.question(), 256), truncate(answer.answer() == null || answer.answer().isBlank()
                    ? "*keine Angabe*" : answer.answer(), 1024), false);
        }
        if (state == ApplicationState.ACCEPTED) {
            embed.setFooter("✅ Angenommen");
        } else if (state == ApplicationState.DENIED) {
            embed.setFooter("❌ Abgelehnt");
        }
        return embed.build();
    }

    static ActionRow applicationButtons(Ticket ticket) {
        return ActionRow.of(
                Button.success(id("accept", ticket.id()), "Annehmen").withEmoji(Emoji.fromUnicode("✅")),
                Button.danger(id("deny", ticket.id()), "Ablehnen").withEmoji(Emoji.fromUnicode("❌")));
    }

    static MessageCreateData closeRequest(Ticket ticket, long actorId, String reason, Instant until) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(Brand.WARNING)
                .setTitle("Kann das Ticket geschlossen werden?")
                .setDescription("<@" + actorId + "> möchte dieses Ticket schließen."
                        + (reason == null ? "" : "\n\n**Grund:** " + truncate(reason, 1000))
                        + "\n\nWenn bis <t:" + until.getEpochSecond() + ":f> (<t:" + until.getEpochSecond()
                        + ":R>) keine Antwort kommt, wird es automatisch geschlossen.");
        return new MessageCreateBuilder()
                .setContent("<@" + ticket.creatorId() + ">")
                .setEmbeds(embed.build())
                .setComponents(ActionRow.of(
                        Button.success(id("crc", ticket.id()), "Ja, schließen").withEmoji(Emoji.fromUnicode("✅")),
                        Button.secondary(id("crd", ticket.id()), "Nein, offen lassen").withEmoji(Emoji.fromUnicode("✋"))))
                .build();
    }

    static MessageEmbed notice(String text) {
        return new EmbedBuilder().setColor(Brand.PRIMARY).setDescription(truncate(text, 4096)).build();
    }

    static MessageEmbed notice(Color color, String text) {
        return new EmbedBuilder().setColor(color).setDescription(truncate(text, 4096)).build();
    }

    static MessageEmbed log(Ticket ticket, int messages, List<Long> participants, String transcriptUrl, boolean inlineLink) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(Brand.DANGER)
                .setTitle("🔒 Ticket #" + ticket.number() + " geschlossen")
                .addField("📂 Kategorie", ticket.categoryName(), true)
                .addField("👤 Geöffnet von", "<@" + ticket.creatorId() + ">" + (ticket.openedBy() != ticket.creatorId()
                        ? "\n-# erstellt von <@" + ticket.openedBy() + ">" : ""), true)
                .addField("🔒 Geschlossen von", ticket.closedBy() == null ? "–" : "<@" + ticket.closedBy() + ">", true)
                .addField("🙋 Bearbeitet von", ticket.claimedBy() == null ? "–" : "<@" + ticket.claimedBy() + ">", true)
                .addField("📅 Geöffnet", "<t:" + ticket.createdAt().getEpochSecond() + ":f>", true)
                .addField("⏱️ Dauer", duration(ticket.duration()), true)
                .addField("⚡ Erste Antwort", ticket.firstResponseAt() == null
                        ? "–" : "nach " + duration(Duration.between(ticket.createdAt(), ticket.firstResponseAt())), true)
                .addField("💬 Nachrichten", String.valueOf(messages), true)
                .addField("👥 Teilnehmer", participants.isEmpty()
                        ? "–" : truncate(participants.stream().map(id -> "<@" + id + ">").collect(Collectors.joining(" ")), 1024), true);
        if (ticket.applicationState() != null && ticket.applicationState() != ApplicationState.PENDING) {
            embed.addField("📋 Bewerbung", ticket.applicationState() == ApplicationState.ACCEPTED ? "✅ Angenommen" : "❌ Abgelehnt", false);
        }
        embed.addField("📝 Grund", ticket.closeReason() == null ? "Kein Grund angegeben" : truncate(ticket.closeReason(), 1024), false);
        if (transcriptUrl != null && inlineLink) {
            embed.addField("🔗 Transkript", transcriptUrl, false);
        }
        embed.setFooter("Ticket-ID " + ticket.uuid());
        embed.setTimestamp(ticket.closedAt() == null ? Instant.now() : ticket.closedAt());
        return embed.build();
    }

    static MessageEmbed summary(Guild guild, Ticket ticket, String transcriptUrl, boolean rating, boolean inlineLink) {
        StringBuilder text = new StringBuilder("Dein Ticket **#").append(ticket.number()).append(" · ").append(ticket.categoryName())
                .append("** auf **").append(guild.getName()).append("** wurde geschlossen.");
        if (ticket.closeReason() != null) {
            text.append("\n\n**Grund:** ").append(truncate(ticket.closeReason(), 1000));
        }
        if (transcriptUrl != null) {
            text.append("\n\nDen Verlauf kannst du dir jederzeit ansehen, nachdem du dich im Dashboard angemeldet hast.");
            if (inlineLink) {
                text.append("\n").append(transcriptUrl);
            }
        }
        if (rating) {
            text.append("\n\n**Wie zufrieden warst du")
                    .append(ticket.claimedBy() == null ? " mit dem Support" : " mit <@" + ticket.claimedBy() + ">")
                    .append("?** Deine Bewertung hilft dem Team.");
        }
        return new EmbedBuilder().setColor(Brand.PRIMARY).setTitle("Ticket geschlossen").setDescription(text).build();
    }

    static List<ActionRow> summaryButtons(Ticket ticket, String transcriptUrl, boolean rating) {
        List<ActionRow> rows = new ArrayList<>();
        if (rating) {
            List<Button> stars = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                stars.add(Button.secondary(id("rate", ticket.id(), i), "⭐".repeat(i)));
            }
            rows.add(ActionRow.of(stars));
        }
        if (transcriptUrl != null) {
            rows.add(ActionRow.of(Button.link(transcriptUrl, "Verlauf ansehen").withEmoji(Emoji.fromUnicode("📜"))));
        }
        return rows;
    }

    static List<ActionRow> transcriptButton(String transcriptUrl) {
        return transcriptUrl != null
                ? List.of(ActionRow.of(Button.link(transcriptUrl, "Transkript ansehen").withEmoji(Emoji.fromUnicode("📜"))))
                : List.of();
    }

    static String channelName(String pattern, Ticket ticket, String username) {
        String name = (pattern == null || pattern.isBlank() ? "{category}-{number}" : pattern)
                .replace("{number}", String.format("%04d", ticket.number()))
                .replace("{category}", ticket.categoryName())
                .replace("{username}", username)
                .replace("{user}", username);
        String cleaned = name.toLowerCase().strip()
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}_-]", "")
                .replaceAll("-{2,}", "-");
        if (cleaned.isBlank()) {
            cleaned = "ticket-" + ticket.number();
        }
        return truncate(cleaned, 100);
    }

    static String duration(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if (minutes < 1) {
            return "unter 1 Minute";
        }
        long days = minutes / (60 * 24);
        long hours = (minutes / 60) % 24;
        long rest = minutes % 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + (days == 1 ? " Tag" : " Tage"));
        }
        if (hours > 0) {
            parts.add(hours + " Std.");
        }
        if (rest > 0 && days == 0) {
            parts.add(rest + " Min.");
        }
        return String.join(" ", parts);
    }

    static Set<Long> pingRoles(Category category, Settings settings) {
        Set<Long> roles = new LinkedHashSet<>();
        if (category != null && category.pingSupport()) {
            roles.addAll(category.supportRoleIds());
            if (category.supportRoleIds().isEmpty() && category.includeStaff()) {
                roles.addAll(settings.staffRoleIds());
            }
        }
        if (category != null) {
            roles.addAll(category.pingRoleIds());
        }
        return roles;
    }

    static Emoji emoji(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Emoji.fromFormatted(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    private static UnaryOperator<String> placeholders(Guild guild, Ticket ticket) {
        Map<String, String> values = Map.of(
                "{user}", "<@" + ticket.creatorId() + ">",
                "{username}", ticket.creatorName(),
                "{category}", ticket.categoryName(),
                "{number}", String.valueOf(ticket.number()),
                "{server}", guild.getName());
        return text -> {
            if (text == null) {
                return null;
            }
            String result = text;
            for (var entry : values.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        };
    }

    private static MessagePayload fill(MessagePayload payload, UnaryOperator<String> fill) {
        List<Embed> embeds = payload.embeds().stream().map(embed -> new Embed(
                fill.apply(embed.title()),
                fill.apply(embed.description()),
                embed.url(),
                embed.color(),
                embed.timestamp(),
                embed.author() == null ? null : new MessagePayload.Author(fill.apply(embed.author().name()), embed.author().url(),
                        embed.author().iconUrl()),
                embed.footer() == null ? null : new Footer(fill.apply(embed.footer().text()), embed.footer().iconUrl()),
                embed.thumbnail(),
                embed.image(),
                embed.fields().stream().map(field -> new Field(fill.apply(field.name()), fill.apply(field.value()), field.inline())).toList()))
                .toList();
        return new MessagePayload(fill.apply(payload.content()), embeds);
    }

    private static ButtonStyle style(ButtonColor color) {
        return switch (color) {
            case PRIMARY -> ButtonStyle.PRIMARY;
            case SECONDARY -> ButtonStyle.SECONDARY;
            case SUCCESS -> ButtonStyle.SUCCESS;
            case DANGER -> ButtonStyle.DANGER;
        };
    }
}
