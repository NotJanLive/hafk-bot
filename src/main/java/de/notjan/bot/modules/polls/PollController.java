package de.notjan.bot.modules.polls;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.modules.polls.Poll.Draft;
import de.notjan.bot.modules.polls.Poll.Option;
import de.notjan.bot.modules.polls.Poll.OptionDraft;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.modules.polls.PollRepository.Tally;
import io.javalin.http.Context;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

final class PollController implements ApiController {

    record VoterDto(String id, String name, String avatarUrl) {

        static VoterDto of(Guild guild, long userId) {
            Member member = guild.getMemberById(userId);
            return member == null
                    ? new VoterDto(String.valueOf(userId), null, null)
                    : new VoterDto(member.getId(), member.getEffectiveName(), member.getEffectiveAvatarUrl());
        }
    }

    record OptionDto(String id, String label, String emoji, Integer votes, Integer percent, List<VoterDto> voters) {
    }

    record PollDto(String id, String channelId, String channelName, String messageId, String jumpUrl, String question,
                   String description, boolean anonymous, ResultVisibility visibility, boolean hostResults, int maxChoices,
                   boolean allowChange, String pingRoleId, Instant endsAt, Instant closedAt, boolean cancelled, String createdBy, String createdByName, Instant createdAt,
                   List<String> allowedRoleIds, int participants, boolean resultsVisible, List<OptionDto> options) {

        static PollDto of(Guild guild, Poll poll, Tally tally, boolean withVoters) {
            GuildChannel channel = guild.getGuildChannelById(poll.channelId());
            Member creator = guild.getMemberById(poll.createdBy());
            boolean visible = poll.managersSeeResults();
            List<OptionDto> options = poll.options().stream().map(option -> option(guild, poll, option, tally, visible, withVoters)).toList();
            return new PollDto(String.valueOf(poll.id()), String.valueOf(poll.channelId()), channel == null ? null : channel.getName(),
                    poll.message() == null ? null : String.valueOf(poll.message().messageId()),
                    poll.message() == null ? null : poll.message().jumpUrl(),
                    poll.question(), poll.description(), poll.anonymous(), poll.visibility(), poll.hostResults(), poll.maxChoices(),
                    poll.allowChange(), poll.pingRoleId() == null ? null : String.valueOf(poll.pingRoleId()), poll.endsAt(),
                    poll.closedAt(), poll.cancelled(), String.valueOf(poll.createdBy()),
                    creator == null ? null : creator.getEffectiveName(), poll.createdAt(),
                    poll.allowedRoleIds().stream().map(String::valueOf).toList(), tally.voters(), visible, options);
        }

        private static OptionDto option(Guild guild, Poll poll, Option option, Tally tally, boolean visible, boolean withVoters) {
            if (!visible) {
                return new OptionDto(String.valueOf(option.id()), option.label(), option.emoji(), null, null, null);
            }
            int votes = tally.votes(option.id());
            List<VoterDto> voters = withVoters && !poll.anonymous()
                    ? tally.voters(option.id()).stream().map(userId -> VoterDto.of(guild, userId)).toList()
                    : null;
            return new OptionDto(String.valueOf(option.id()), option.label(), option.emoji(), votes,
                    PollRenderer.percent(votes, tally.voters()), voters);
        }
    }

    record OptionRequest(String label, String emoji) {
    }

    record PollRequest(String channelId, String question, String description, boolean anonymous, ResultVisibility visibility,
                       boolean hostResults, int maxChoices, boolean allowChange, String pingRoleId, Integer durationMinutes,
                       List<OptionRequest> options,
                       List<String> allowedRoleIds) {

        Draft toDraft() {
            List<OptionDraft> converted = options == null
                    ? List.of()
                    : options.stream().map(option -> new OptionDraft(option.label(), option.emoji())).toList();
            return new Draft(GuildGuard.snowflake(channelId, "channelId"), question, description, anonymous, visibility, hostResults,
                    maxChoices, allowChange, pingRoleId == null || pingRoleId.isBlank() ? null : GuildGuard.snowflake(pingRoleId, "pingRoleId"),
                    durationMinutes, converted, snowflakes(allowedRoleIds, "allowedRoleIds"));
        }
    }

    record SettingsDto(List<String> creatorRoleIds) {
    }

    private final GuildGuard guard;
    private final PollService service;

    PollController(GuildGuard guard, PollService service) {
        this.guard = guard;
        this.service = service;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}/polls/settings", this::getSettings);
        put("/guilds/{guildId}/polls/settings", this::putSettings);
        get("/guilds/{guildId}/polls", this::list);
        post("/guilds/{guildId}/polls", this::create);
        get("/guilds/{guildId}/polls/{id}", this::getPoll);
        post("/guilds/{guildId}/polls/{id}/close", this::close);
        delete("/guilds/{guildId}/polls/{id}", this::deletePoll);
    }

    private void list(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Guild guild = request.guild();
        ctx.json(service.list(guild.getIdLong()).stream().map(poll -> PollDto.of(guild, poll, service.tally(poll, true), true)).toList());
    }

    private void getPoll(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Poll poll = service.require(request.guild().getIdLong(), ApiController.longParam(ctx, "id"));
        ctx.json(PollDto.of(request.guild(), poll, service.tally(poll, true), true));
    }

    private void create(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Draft draft = ApiController.readBody(ctx, PollRequest.class).toDraft();
        Poll poll = service.create(request.guild(), request.userId(), Source.DASHBOARD, draft);
        ctx.status(201).json(PollDto.of(request.guild(), poll, service.tally(poll, false), false));
    }

    private void close(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Poll poll = service.close(request.guild(), request.userId(), Source.DASHBOARD, ApiController.longParam(ctx, "id"),
                Boolean.parseBoolean(ctx.queryParam("cancel")));
        ctx.json(PollDto.of(request.guild(), poll, service.tally(poll, true), true));
    }

    private void deletePoll(Context ctx) {
        GuildRequest request = guard.require(ctx);
        service.delete(request.guild(), request.userId(), ApiController.longParam(ctx, "id"),
                Boolean.parseBoolean(ctx.queryParam("deleteMessage")));
        ctx.status(204);
    }

    private void getSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(settings(service.creatorRoles(request.guild().getIdLong())));
    }

    private void putSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        SettingsDto body = ApiController.readBody(ctx, SettingsDto.class);
        ctx.json(settings(service.updateCreatorRoles(request.guild(), request.userId(), snowflakes(body.creatorRoleIds(), "creatorRoleIds"))));
    }

    private static SettingsDto settings(Set<Long> roleIds) {
        return new SettingsDto(roleIds.stream().map(String::valueOf).sorted().toList());
    }

    private static Set<Long> snowflakes(List<String> values, String name) {
        return values == null ? Set.of() : values.stream().map(value -> GuildGuard.snowflake(value, name)).collect(Collectors.toSet());
    }
}
