package de.notjan.bot.util;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.awt.Color;

/**
 * Consistent ephemeral feedback for interactions, regardless of whether they were already acknowledged.
 */
public final class Replies {

    private Replies() {
    }

    public static void success(IReplyCallback event, String message) {
        send(event, embed(Brand.PRIMARY, message));
    }

    public static void error(IReplyCallback event, String message) {
        send(event, embed(Brand.DANGER, message));
    }

    private static MessageEmbed embed(Color color, String message) {
        return new EmbedBuilder().setColor(color).setDescription(message).build();
    }

    private static void send(IReplyCallback event, MessageEmbed embed) {
        if (event.isAcknowledged()) {
            event.getHook().sendMessageEmbeds(embed).setEphemeral(true).queue();
        } else {
            event.replyEmbeds(embed).setEphemeral(true).queue();
        }
    }
}
