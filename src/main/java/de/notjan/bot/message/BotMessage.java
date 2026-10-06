package de.notjan.bot.message;

import java.time.Instant;

public record BotMessage(
        long id,
        long guildId,
        long channelId,
        long messageId,
        String module,
        String label,
        MessagePayload payload,
        long createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    public String jumpUrl() {
        return "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId;
    }
}
