package de.notjan.bot.reset;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.message.BotChannelRepository;
import de.notjan.bot.message.BotChannelRepository.BotChannel;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ResetService {

    private static final Logger LOG = LoggerFactory.getLogger(ResetService.class);

    public record Category(String id, String label, String description, long count, boolean required) {
    }

    public record ChannelItem(String id, String channelId, String name, String module, String label, boolean exists) {
    }

    public record MessageItem(String id, String channelId, String channelName, String messageId, String module, String label,
                              String jumpUrl) {
    }

    public record Preview(List<Category> categories, List<ChannelItem> channels, List<MessageItem> messages) {
    }

    public record Request(List<String> categories, List<String> deleteChannels, List<String> deleteMessages) {
    }

    public record Result(List<String> clearedCategories, int deletedChannels, int deletedMessages, List<String> problems) {
    }

    private final Map<String, ResettableData> categories = new LinkedHashMap<>();
    private final BotMessageService messages;
    private final BotChannelRepository channels;
    private final AuditLogService audit;

    public ResetService(List<ResettableData> categories, BotMessageService messages, BotChannelRepository channels, AuditLogService audit) {
        categories.forEach(category -> this.categories.put(category.id(), category));
        this.messages = messages;
        this.channels = channels;
        this.audit = audit;
    }

    public Preview preview(Guild guild) {
        long guildId = guild.getIdLong();
        List<Category> categoryItems = categories.values().stream()
                .map(category -> new Category(category.id(), category.label(), category.description(),
                        category.count(guildId), category.required()))
                .toList();
        List<ChannelItem> channelItems = channels.list(guildId).stream()
                .map(channel -> {
                    GuildChannel live = guild.getGuildChannelById(channel.channelId());
                    return new ChannelItem(String.valueOf(channel.id()), String.valueOf(channel.channelId()),
                            live == null ? channel.label() : live.getName(), channel.module(), channel.label(), live != null);
                })
                .toList();
        List<MessageItem> messageItems = messages.list(guildId, null).stream()
                .map(message -> {
                    GuildChannel channel = guild.getGuildChannelById(message.channelId());
                    return new MessageItem(String.valueOf(message.id()), String.valueOf(message.channelId()),
                            channel == null ? "gelöschter Kanal" : channel.getName(), String.valueOf(message.messageId()),
                            message.module(), message.label(), message.jumpUrl());
                })
                .toList();
        return new Preview(categoryItems, channelItems, messageItems);
    }

    public Result execute(Guild guild, long userId, Request request) {
        long guildId = guild.getIdLong();
        Set<String> selected = new HashSet<>(request.categories() == null ? List.of() : request.categories());
        for (String id : selected) {
            if (!categories.containsKey(id)) {
                throw new UserFacingException("Unbekannter Bereich: " + id);
            }
        }
        categories.values().stream().filter(ResettableData::required).forEach(category -> selected.add(category.id()));

        List<String> problems = new ArrayList<>();
        int deletedChannels = 0;
        for (long id : ids(request.deleteChannels())) {
            BotChannel channel = channels.find(guildId, id).orElse(null);
            if (channel == null) {
                continue;
            }
            GuildChannel live = guild.getGuildChannelById(channel.channelId());
            try {
                if (live != null) {
                    live.delete().complete();
                }
                channels.delete(id);
                deletedChannels++;
            } catch (ErrorResponseException | InsufficientPermissionException e) {
                problems.add("Kanal „" + channel.label() + "“ konnte nicht gelöscht werden.");
            }
        }

        int deletedMessages = 0;
        for (long id : ids(request.deleteMessages())) {
            BotMessage message = messages.find(guildId, id).orElse(null);
            if (message == null) {
                continue;
            }
            try {
                messages.delete(guild, message, true);
                deletedMessages++;
            } catch (UserFacingException | InsufficientPermissionException e) {
                problems.add("Nachricht „" + message.label() + "“ konnte nicht gelöscht werden.");
            }
        }

        List<String> cleared = new ArrayList<>();
        for (ResettableData category : categories.values()) {
            if (selected.contains(category.id())) {
                category.delete(guildId);
                cleared.add(category.label());
            }
        }

        String summary = "Bot zurückgesetzt: " + String.join(", ", cleared)
                + (deletedChannels > 0 ? ", " + deletedChannels + " Kanäle gelöscht" : "")
                + (deletedMessages > 0 ? ", " + deletedMessages + " Nachrichten gelöscht" : "");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("categories", List.copyOf(selected));
        details.put("deletedChannels", deletedChannels);
        details.put("deletedMessages", deletedMessages);
        audit.record(guild, userId, Source.DASHBOARD, "bot.reset", summary, details);
        LOG.info("Guild {} reset by {}: {}", guildId, userId, summary);

        return new Result(cleared, deletedChannels, deletedMessages, problems);
    }

    private static List<Long> ids(List<String> values) {
        if (values == null) {
            return List.of();
        }
        try {
            return values.stream().map(Long::parseLong).toList();
        } catch (NumberFormatException e) {
            throw new UserFacingException("Ungültige Auswahl.");
        }
    }
}
