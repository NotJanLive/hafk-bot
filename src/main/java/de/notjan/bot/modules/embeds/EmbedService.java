package de.notjan.bot.modules.embeds;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.message.PayloadValidator;
import de.notjan.bot.reset.ResettableData;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EmbedService {

    static final String MODULE = "embeds";
    static final int MAX_TEMPLATES = 100;
    static final int MAX_NAME = 100;

    private final BotMessageService messages;
    private final EmbedTemplateRepository templates;
    private final AuditLogService audit;

    public EmbedService(BotMessageService messages, EmbedTemplateRepository templates, AuditLogService audit) {
        this.messages = messages;
        this.templates = templates;
        this.audit = audit;
    }

    public List<BotMessage> sent(long guildId) {
        return messages.list(guildId, MODULE);
    }

    public BotMessage requireSent(long guildId, long id) {
        return messages.find(guildId, id)
                .filter(message -> MODULE.equals(message.module()))
                .orElseThrow(() -> UserFacingException.notFound("Diese Embed-Nachricht ist nicht (mehr) bekannt."));
    }

    public Optional<BotMessage> findSentByMessageId(long guildId, long messageId) {
        return messages.findByMessageId(messageId)
                .filter(message -> message.guildId() == guildId && MODULE.equals(message.module()));
    }

    public BotMessage send(Guild guild, long userId, Source source, long channelId, String label, MessagePayload payload) {
        String name = label(label, payload);
        BotMessage sent = messages.send(guild, channelId, MODULE, name, payload, List.of(), userId).record();
        audit.record(guild, userId, source, "embeds.send", "Embed „" + name + "“ in <#" + channelId + "> gesendet",
                Map.of("messageId", String.valueOf(sent.messageId())));
        return sent;
    }

    public BotMessage edit(Guild guild, long userId, Source source, long id, String label, MessagePayload payload) {
        BotMessage existing = requireSent(guild.getIdLong(), id);
        String name = label(label == null ? existing.label() : label, payload);
        BotMessage updated = messages.edit(guild, existing, name, payload, List.of());
        audit.record(guild, userId, source, "embeds.edit", "Embed „" + name + "“ in <#" + existing.channelId() + "> bearbeitet",
                Map.of("messageId", String.valueOf(existing.messageId())));
        return updated;
    }

    public void deleteSent(Guild guild, long userId, long id, boolean inDiscord) {
        BotMessage existing = requireSent(guild.getIdLong(), id);
        messages.delete(guild, existing, inDiscord);
        audit.record(guild, userId, Source.DASHBOARD, "embeds.delete",
                (inDiscord ? "Embed „" + existing.label() + "“ gelöscht" : "Embed „" + existing.label() + "“ aus der Liste entfernt"),
                Map.of("messageId", String.valueOf(existing.messageId())));
    }

    public List<EmbedTemplate> templates(long guildId) {
        return templates.list(guildId);
    }

    public EmbedTemplate requireTemplate(long guildId, long id) {
        return templates.find(guildId, id).orElseThrow(() -> UserFacingException.notFound("Diese Vorlage existiert nicht."));
    }

    public Optional<EmbedTemplate> findTemplateByName(long guildId, String name) {
        return templates.findByName(guildId, name);
    }

    public EmbedTemplate createTemplate(Guild guild, long userId, String name, MessagePayload payload) {
        String cleanName = templateName(name);
        PayloadValidator.require(payload);
        long guildId = guild.getIdLong();
        if (templates.count(guildId) >= MAX_TEMPLATES) {
            throw new UserFacingException("Es sind höchstens " + MAX_TEMPLATES + " Vorlagen pro Server möglich.");
        }
        if (templates.findByName(guildId, cleanName).isPresent()) {
            throw new UserFacingException("Es gibt bereits eine Vorlage mit dem Namen „" + cleanName + "“.");
        }
        long id = templates.insert(guildId, cleanName, payload, userId);
        audit.record(guild, userId, Source.DASHBOARD, "embeds.template.create", "Embed-Vorlage „" + cleanName + "“ erstellt", Map.of());
        return requireTemplate(guildId, id);
    }

    public EmbedTemplate updateTemplate(Guild guild, long userId, long id, String name, MessagePayload payload) {
        long guildId = guild.getIdLong();
        EmbedTemplate existing = requireTemplate(guildId, id);
        String cleanName = templateName(name);
        PayloadValidator.require(payload);
        templates.findByName(guildId, cleanName)
                .filter(other -> other.id() != id)
                .ifPresent(other -> {
                    throw new UserFacingException("Es gibt bereits eine Vorlage mit dem Namen „" + cleanName + "“.");
                });
        templates.update(id, cleanName, payload);
        audit.record(guild, userId, Source.DASHBOARD, "embeds.template.update", "Embed-Vorlage „" + existing.name() + "“ bearbeitet", Map.of());
        return requireTemplate(guildId, id);
    }

    public void deleteTemplate(Guild guild, long userId, long id) {
        EmbedTemplate existing = requireTemplate(guild.getIdLong(), id);
        templates.delete(id);
        audit.record(guild, userId, Source.DASHBOARD, "embeds.template.delete", "Embed-Vorlage „" + existing.name() + "“ gelöscht", Map.of());
    }

    List<ResettableData> resettableData() {
        return List.of(
                ResettableData.of("embeds.templates", "Embed-Vorlagen", "Alle gespeicherten Vorlagen",
                        templates::count, templates::deleteAll),
                ResettableData.of("embeds.messages", "Liste gesendeter Embeds",
                        "Die Einträge zum Bearbeiten. Die Nachrichten selbst bleiben in Discord, außer du wählst sie unten aus.",
                        guildId -> messages.count(guildId, MODULE), guildId -> messages.deleteRecords(guildId, MODULE)));
    }

    static String label(String label, MessagePayload payload) {
        String candidate = label;
        if (candidate == null || candidate.isBlank()) {
            candidate = payload.firstEmbed().title();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = payload.content();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "Embed";
        }
        candidate = candidate.strip().replaceAll("\\s+", " ");
        return candidate.length() > MAX_NAME ? candidate.substring(0, MAX_NAME - 1) + "…" : candidate;
    }

    private static String templateName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new UserFacingException("Der Name der Vorlage muss 1 bis " + MAX_NAME + " Zeichen lang sein.");
        }
        return clean;
    }
}
