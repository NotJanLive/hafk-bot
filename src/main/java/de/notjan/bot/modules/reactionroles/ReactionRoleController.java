package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Draft;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Mode;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Option;
import de.notjan.bot.modules.reactionroles.ReactionRolePanel.Type;
import io.javalin.http.Context;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Instant;
import java.util.List;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

final class ReactionRoleController implements ApiController {

    record OptionDto(String roleId, String label, String emoji, String description, String style) {

        static OptionDto of(Option option) {
            return new OptionDto(String.valueOf(option.roleId()), option.label(), option.emoji(), option.description(), option.style());
        }

        Option toOption() {
            return new Option(GuildGuard.snowflake(roleId, "roleId"), blankToNull(label), blankToNull(emoji),
                    blankToNull(description), blankToNull(style));
        }
    }

    record PanelDto(String id, String channelId, String channelName, String messageId, String jumpUrl, String label, Type type,
                    Mode mode, MessagePayload payload, List<OptionDto> options, Instant updatedAt) {

        static PanelDto of(Guild guild, ReactionRolePanel panel) {
            GuildChannel channel = guild.getGuildChannelById(panel.message().channelId());
            return new PanelDto(String.valueOf(panel.id()), String.valueOf(panel.message().channelId()),
                    channel == null ? null : channel.getName(), String.valueOf(panel.message().messageId()), panel.message().jumpUrl(),
                    panel.message().label(), panel.type(), panel.mode(), panel.message().payload(),
                    panel.options().stream().map(OptionDto::of).toList(), panel.message().updatedAt());
        }
    }

    record PanelRequest(String channelId, String label, Type type, Mode mode, MessagePayload payload, List<OptionDto> options) {

        Draft toDraft(boolean requireChannel) {
            long channel = requireChannel ? GuildGuard.snowflake(channelId, "channelId") : 0L;
            List<Option> converted = options == null ? List.of() : options.stream().map(OptionDto::toOption).toList();
            return new Draft(channel, label, type, mode, payload, converted);
        }
    }

    private final GuildGuard guard;
    private final ReactionRoleService service;

    ReactionRoleController(GuildGuard guard, ReactionRoleService service) {
        this.guard = guard;
        this.service = service;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}/reaction-roles", this::list);
        post("/guilds/{guildId}/reaction-roles", this::create);
        get("/guilds/{guildId}/reaction-roles/{id}", this::getPanel);
        put("/guilds/{guildId}/reaction-roles/{id}", this::update);
        delete("/guilds/{guildId}/reaction-roles/{id}", this::deletePanel);
    }

    private void list(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(service.list(request.guild().getIdLong()).stream().map(panel -> PanelDto.of(request.guild(), panel)).toList());
    }

    private void getPanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(PanelDto.of(request.guild(), service.require(request.guild().getIdLong(), ApiController.longParam(ctx, "id"))));
    }

    private void create(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Draft draft = ApiController.readBody(ctx, PanelRequest.class).toDraft(true);
        ctx.status(201).json(PanelDto.of(request.guild(), service.create(request.guild(), request.userId(), draft)));
    }

    private void update(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Draft draft = ApiController.readBody(ctx, PanelRequest.class).toDraft(false);
        ctx.json(PanelDto.of(request.guild(), service.update(request.guild(), request.userId(), ApiController.longParam(ctx, "id"), draft)));
    }

    private void deletePanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        service.delete(request.guild(), request.userId(), ApiController.longParam(ctx, "id"),
                Boolean.parseBoolean(ctx.queryParam("deleteMessage")));
        ctx.status(204);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
