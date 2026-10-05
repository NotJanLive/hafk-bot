package de.notjan.bot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Records who changed what. Every entry is persisted and, if configured, posted to the log channel.
 */
public final class AuditLogService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;
    private final GuildSettingsService settings;
    private final ObjectMapper json;

    public AuditLogService(AuditLogRepository repository, GuildSettingsService settings, ObjectMapper json) {
        this.repository = repository;
        this.settings = settings;
        this.json = json;
    }

    /**
     * @param summary human readable German sentence, e.g. "Log-Kanal auf #logs gesetzt"
     */
    public void record(Guild guild, long userId, Source source, String action, String summary, Map<String, ?> details) {
        repository.insert(guild.getIdLong(), userId, source, action, summary, toJson(details));
        settings.get(guild.getIdLong()).logChannel()
                .map(guild::getTextChannelById)
                .ifPresent(channel -> postToLogChannel(channel, userId, source, summary));
    }

    public List<AuditEntry> recent(long guildId, int limit) {
        return repository.recent(guildId, limit);
    }

    private void postToLogChannel(TextChannel channel, long userId, Source source, String summary) {
        if (!channel.canTalk()) {
            LOG.debug("Cannot write to log channel {} in guild {}", channel.getId(), channel.getGuild().getId());
            return;
        }
        var embed = new EmbedBuilder()
                .setColor(Brand.INFO)
                .setDescription(summary)
                .addField("Von", "<@" + userId + ">", true)
                .addField("Quelle", source == Source.DASHBOARD ? "Dashboard" : "Discord", true)
                .setTimestamp(Instant.now())
                .build();
        channel.sendMessageEmbeds(embed).queue(null, error -> LOG.warn("Failed to post audit entry to log channel", error));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serializable", e);
        }
    }
}
