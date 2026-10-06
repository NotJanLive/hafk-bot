package de.notjan.bot.message;

import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;

public final class BotMessageCleanupListener extends ListenerAdapter {

    private final BotMessageRepository messages;
    private final BotChannelRepository channels;

    public BotMessageCleanupListener(BotMessageRepository messages, BotChannelRepository channels) {
        this.messages = messages;
        this.channels = channels;
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (event.isFromGuild()) {
            messages.deleteByMessageIds(List.of(event.getMessageIdLong()));
        }
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        messages.deleteByMessageIds(event.getMessageIds().stream().map(Long::parseLong).toList());
    }

    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        if (event.isFromGuild()) {
            messages.deleteByChannel(event.getChannel().getIdLong());
            channels.deleteByChannel(event.getChannel().getIdLong());
        }
    }
}
