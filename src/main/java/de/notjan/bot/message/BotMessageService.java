package de.notjan.bot.message;

import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.List;
import java.util.Optional;

public final class BotMessageService {

    public record Sent(BotMessage record, Message message) {
    }

    private final BotMessageRepository repository;

    public BotMessageService(BotMessageRepository repository) {
        this.repository = repository;
    }

    public Sent send(Guild guild, long channelId, String module, String label, MessagePayload payload,
                     List<ActionRow> components, long userId) {
        PayloadValidator.require(payload);
        GuildMessageChannel channel = writableChannel(guild, channelId);
        Message message;
        try {
            message = channel.sendMessage(PayloadRenderer.create(payload, components)).complete();
        } catch (ErrorResponseException | IllegalArgumentException e) {
            throw new UserFacingException("Discord hat die Nachricht abgelehnt: " + e.getMessage());
        }
        long id = repository.insert(guild.getIdLong(), channelId, message.getIdLong(), module, label, payload, userId);
        return new Sent(repository.find(guild.getIdLong(), id).orElseThrow(), message);
    }

    public BotMessage edit(Guild guild, BotMessage existing, String label, MessagePayload payload, List<ActionRow> components) {
        PayloadValidator.require(payload);
        GuildMessageChannel channel = writableChannel(guild, existing.channelId());
        try {
            channel.editMessageById(existing.messageId(), PayloadRenderer.edit(payload, components)).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                repository.delete(existing.id());
                throw UserFacingException.notFound("Die Nachricht wurde in Discord bereits gelöscht.");
            }
            throw new UserFacingException("Discord hat die Änderung abgelehnt: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new UserFacingException("Discord hat die Änderung abgelehnt: " + e.getMessage());
        }
        repository.update(existing.id(), label, payload);
        return repository.find(guild.getIdLong(), existing.id()).orElseThrow();
    }

    public void delete(Guild guild, BotMessage message, boolean inDiscord) {
        if (inDiscord) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, message.channelId());
            if (channel != null) {
                try {
                    channel.deleteMessageById(message.messageId()).complete();
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                        throw new UserFacingException("Die Nachricht konnte in Discord nicht gelöscht werden: " + e.getMeaning());
                    }
                }
            }
        }
        repository.delete(message.id());
    }

    public Optional<BotMessage> find(long guildId, long id) {
        return repository.find(guildId, id);
    }

    public Optional<BotMessage> findByMessageId(long messageId) {
        return repository.findByMessageId(messageId);
    }

    public List<BotMessage> list(long guildId, String module) {
        return repository.list(guildId, module);
    }

    public long count(long guildId, String module) {
        return repository.count(guildId, module);
    }

    public void deleteRecords(long guildId, String module) {
        repository.deleteByModule(guildId, module);
    }

    public static GuildMessageChannel writableChannel(Guild guild, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            throw new UserFacingException("Der Kanal existiert nicht oder ist kein Textkanal.");
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            throw new UserFacingException("Der Bot darf in #" + channel.getName() + " keine Nachrichten mit Embeds senden.");
        }
        return channel;
    }
}
