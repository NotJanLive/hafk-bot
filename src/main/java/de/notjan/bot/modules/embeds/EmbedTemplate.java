package de.notjan.bot.modules.embeds;

import de.notjan.bot.message.MessagePayload;

import java.time.Instant;

public record EmbedTemplate(long id, long guildId, String name, MessagePayload payload, long createdBy, Instant createdAt,
                            Instant updatedAt) {
}
