package de.notjan.bot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.audit.AuditEntry.Source;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;
import java.util.Map;

/**
 * Records who changed what. Entries are shown in the dashboard's audit log.
 */
public final class AuditLogService {

    private final AuditLogRepository repository;
    private final ObjectMapper json;

    public AuditLogService(AuditLogRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * @param summary human readable German sentence, e.g. "Dashboard-Rollen: <@&123>"
     */
    public void record(Guild guild, long userId, Source source, String action, String summary, Map<String, ?> details) {
        repository.insert(guild.getIdLong(), userId, source, action, summary, toJson(details));
    }

    public List<AuditEntry> recent(long guildId, int limit) {
        return repository.recent(guildId, limit);
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serializable", e);
        }
    }
}
