package de.notjan.bot.message;

import de.notjan.bot.message.MessagePayload.Embed;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.time.OffsetDateTime;
import java.util.List;

public final class PayloadRenderer {

    private PayloadRenderer() {
    }

    public static MessageCreateData create(MessagePayload payload, List<ActionRow> components) {
        var builder = new MessageCreateBuilder()
                .setEmbeds(embeds(payload))
                .setComponents(components);
        if (!PayloadValidator.isBlank(payload.content())) {
            builder.setContent(payload.content());
        }
        return builder.build();
    }

    public static MessageEditData edit(MessagePayload payload, List<ActionRow> components) {
        return new MessageEditBuilder()
                .setContent(PayloadValidator.isBlank(payload.content()) ? "" : payload.content())
                .setEmbeds(embeds(payload))
                .setComponents(components)
                .build();
    }

    private static List<MessageEmbed> embeds(MessagePayload payload) {
        return payload.embeds().stream().map(PayloadRenderer::embed).toList();
    }

    static MessageEmbed embed(Embed embed) {
        EmbedBuilder builder = new EmbedBuilder();
        if (!PayloadValidator.isBlank(embed.title())) {
            builder.setTitle(embed.title(), blankToNull(embed.url()));
        }
        builder.setDescription(embed.description());
        if (embed.color() != null) {
            builder.setColor(embed.color());
        }
        if (embed.author() != null && !PayloadValidator.isBlank(embed.author().name())) {
            builder.setAuthor(embed.author().name(), blankToNull(embed.author().url()), blankToNull(embed.author().iconUrl()));
        }
        if (embed.footer() != null && !PayloadValidator.isBlank(embed.footer().text())) {
            builder.setFooter(embed.footer().text(), blankToNull(embed.footer().iconUrl()));
        }
        if (embed.thumbnail() != null && !PayloadValidator.isBlank(embed.thumbnail().url())) {
            builder.setThumbnail(embed.thumbnail().url());
        }
        if (embed.image() != null && !PayloadValidator.isBlank(embed.image().url())) {
            builder.setImage(embed.image().url());
        }
        if (embed.timestamp() != null) {
            builder.setTimestamp(OffsetDateTime.parse(embed.timestamp()));
        }
        embed.fields().forEach(field -> builder.addField(field.name(), field.value(), field.inline()));
        return builder.build();
    }

    private static String blankToNull(String value) {
        return PayloadValidator.isBlank(value) ? null : value;
    }
}
