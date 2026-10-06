package de.notjan.bot.modules.embeds;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.message.BotMessage;
import de.notjan.bot.message.MessagePayload;
import io.javalin.http.Context;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Instant;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

final class EmbedController implements ApiController {

    record SentMessageDto(String id, String channelId, String channelName, String messageId, String label, MessagePayload payload,
                          String createdBy, Instant createdAt, Instant updatedAt, String jumpUrl) {

        static SentMessageDto of(Guild guild, BotMessage message) {
            GuildChannel channel = guild.getGuildChannelById(message.channelId());
            return new SentMessageDto(String.valueOf(message.id()), String.valueOf(message.channelId()),
                    channel == null ? null : channel.getName(), String.valueOf(message.messageId()), message.label(),
                    message.payload(), String.valueOf(message.createdBy()), message.createdAt(), message.updatedAt(), message.jumpUrl());
        }
    }

    record TemplateDto(String id, String name, MessagePayload payload, Instant updatedAt) {

        static TemplateDto of(EmbedTemplate template) {
            return new TemplateDto(String.valueOf(template.id()), template.name(), template.payload(), template.updatedAt());
        }
    }

    record SendRequest(String channelId, String label, MessagePayload payload) {
    }

    record EditRequest(String label, MessagePayload payload) {
    }

    record TemplateRequest(String name, MessagePayload payload) {
    }

    private final GuildGuard guard;
    private final EmbedService embeds;

    EmbedController(GuildGuard guard, EmbedService embeds) {
        this.guard = guard;
        this.embeds = embeds;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}/embeds/messages", this::listSent);
        post("/guilds/{guildId}/embeds/messages", this::send);
        get("/guilds/{guildId}/embeds/messages/{id}", this::getSent);
        put("/guilds/{guildId}/embeds/messages/{id}", this::edit);
        delete("/guilds/{guildId}/embeds/messages/{id}", this::deleteSent);

        get("/guilds/{guildId}/embeds/templates", this::listTemplates);
        post("/guilds/{guildId}/embeds/templates", this::createTemplate);
        get("/guilds/{guildId}/embeds/templates/{id}", this::getTemplate);
        put("/guilds/{guildId}/embeds/templates/{id}", this::updateTemplate);
        delete("/guilds/{guildId}/embeds/templates/{id}", this::deleteTemplate);
    }

    private void listSent(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Guild guild = request.guild();
        ctx.json(embeds.sent(guild.getIdLong()).stream().map(message -> SentMessageDto.of(guild, message)).toList());
    }

    private void getSent(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(SentMessageDto.of(request.guild(), embeds.requireSent(request.guild().getIdLong(), ApiController.longParam(ctx, "id"))));
    }

    private void send(Context ctx) {
        GuildRequest request = guard.require(ctx);
        SendRequest body = ApiController.readBody(ctx, SendRequest.class);
        long channelId = GuildGuard.snowflake(body.channelId(), "channelId");
        BotMessage sent = embeds.send(request.guild(), request.userId(), Source.DASHBOARD, channelId, body.label(), body.payload());
        ctx.status(201).json(SentMessageDto.of(request.guild(), sent));
    }

    private void edit(Context ctx) {
        GuildRequest request = guard.require(ctx);
        EditRequest body = ApiController.readBody(ctx, EditRequest.class);
        BotMessage updated = embeds.edit(request.guild(), request.userId(), Source.DASHBOARD, ApiController.longParam(ctx, "id"),
                body.label(), body.payload());
        ctx.json(SentMessageDto.of(request.guild(), updated));
    }

    private void deleteSent(Context ctx) {
        GuildRequest request = guard.require(ctx);
        boolean inDiscord = Boolean.parseBoolean(ctx.queryParam("deleteMessage"));
        embeds.deleteSent(request.guild(), request.userId(), ApiController.longParam(ctx, "id"), inDiscord);
        ctx.status(204);
    }

    private void listTemplates(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(embeds.templates(request.guild().getIdLong()).stream().map(TemplateDto::of).toList());
    }

    private void getTemplate(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(TemplateDto.of(embeds.requireTemplate(request.guild().getIdLong(), ApiController.longParam(ctx, "id"))));
    }

    private void createTemplate(Context ctx) {
        GuildRequest request = guard.require(ctx);
        TemplateRequest body = ApiController.readBody(ctx, TemplateRequest.class);
        ctx.status(201).json(TemplateDto.of(embeds.createTemplate(request.guild(), request.userId(), body.name(), body.payload())));
    }

    private void updateTemplate(Context ctx) {
        GuildRequest request = guard.require(ctx);
        TemplateRequest body = ApiController.readBody(ctx, TemplateRequest.class);
        ctx.json(TemplateDto.of(embeds.updateTemplate(request.guild(), request.userId(), ApiController.longParam(ctx, "id"),
                body.name(), body.payload())));
    }

    private void deleteTemplate(Context ctx) {
        GuildRequest request = guard.require(ctx);
        embeds.deleteTemplate(request.guild(), request.userId(), ApiController.longParam(ctx, "id"));
        ctx.status(204);
    }
}
