package de.notjan.bot.api.controller;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.ApiException;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import de.notjan.bot.api.dto.AuditEntryDto;
import de.notjan.bot.api.dto.GuildDto;
import de.notjan.bot.api.dto.SettingsDto;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.guild.ChannelAccess;
import de.notjan.bot.guild.ChannelAccess.Visibility;
import de.notjan.bot.guild.GuildSettings;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.guild.GuildSettingsValidator.InvalidSettingsException;
import de.notjan.bot.guild.SettingsUpdater;
import de.notjan.bot.message.BotChannelRepository;
import io.javalin.http.Context;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

public final class GuildController implements ApiController {

    private static final int DEFAULT_AUDIT_LIMIT = 50;
    private static final int MAX_AUDIT_LIMIT = 200;
    private static final int MEMBER_RESULTS = 10;

    private final GuildGuard guard;
    private final GuildSettingsService settings;
    private final SettingsUpdater updater;
    private final AuditLogService audit;
    private final BotChannelRepository channels;

    record CreateChannelRequest(String name, String type, String parentId, String visibility, List<String> roleIds) {
    }

    record AccessRequest(String visibility, List<String> roleIds) {
    }

    public GuildController(GuildGuard guard, GuildSettingsService settings, SettingsUpdater updater, AuditLogService audit,
                           BotChannelRepository channels) {
        this.channels = channels;
        this.guard = guard;
        this.settings = settings;
        this.updater = updater;
        this.audit = audit;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}", this::detail);
        get("/guilds/{guildId}/settings", this::getSettings);
        put("/guilds/{guildId}/settings", this::putSettings);
        get("/guilds/{guildId}/audit-log", this::auditLog);
        get("/guilds/{guildId}/emojis", this::emojis);
        get("/guilds/{guildId}/members", this::members);
        post("/guilds/{guildId}/channels", this::createChannel);
        post("/guilds/{guildId}/channels/{channelId}/access", this::setAccess);
    }

    private void detail(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(GuildDto.Detail.of(request.guild(), request.grant()));
    }

    private void getSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(SettingsDto.of(settings.get(request.guild().getIdLong())));
    }

    private void putSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        SettingsDto body = readBody(ctx);

        Set<Long> roleIds = (body.dashboardRoleIds() == null ? List.<String>of() : body.dashboardRoleIds()).stream()
                .map(id -> GuildGuard.snowflake(id, "dashboardRoleIds"))
                .collect(Collectors.toSet());

        try {
            GuildSettings updated = updater.apply(request.guild(), request.userId(), Source.DASHBOARD, current -> current
                    .withDashboardRoles(roleIds)
                    .withSetupCompleted(current.setupCompleted() || body.setupCompleted()));
            ctx.json(SettingsDto.of(updated));
        } catch (InvalidSettingsException e) {
            throw ApiException.validation(e.errors());
        }
    }

    private void auditLog(Context ctx) {
        GuildRequest request = guard.require(ctx);
        int limit = Math.clamp(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(DEFAULT_AUDIT_LIMIT), 1, MAX_AUDIT_LIMIT);
        Guild guild = request.guild();
        ctx.json(audit.recent(guild.getIdLong(), limit).stream()
                .map(entry -> AuditEntryDto.of(entry, cachedName(guild, entry.userId())))
                .toList());
    }

    private void emojis(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(request.guild().retrieveEmojis().complete().stream()
                .filter(RichCustomEmoji::isAvailable)
                .map(GuildDto.EmojiView::of)
                .toList());
    }

    private void members(Context ctx) {
        GuildRequest request = guard.require(ctx);
        String query = ctx.queryParam("query") == null ? "" : ctx.queryParam("query").strip();
        if (query.isEmpty() || query.length() > 32) {
            ctx.json(List.of());
            return;
        }
        Guild guild = request.guild();
        List<Member> found;
        if (query.matches("\\d{17,20}")) {
            Member member = guild.getMemberById(query);
            if (member == null) {
                try {
                    member = guild.retrieveMemberById(query).complete();
                } catch (ErrorResponseException e) {
                    member = null;
                }
            }
            found = member == null ? List.of() : List.of(member);
        } else {
            found = guild.retrieveMembersByPrefix(query, MEMBER_RESULTS).get();
        }
        ctx.json(found.stream().map(GuildDto.MemberView::of).toList());
    }

    private void createChannel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Guild guild = request.guild();
        CreateChannelRequest body;
        try {
            body = ctx.bodyAsClass(CreateChannelRequest.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Ungültiger JSON-Body.");
        }
        String name = body.name() == null ? "" : body.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.validation(List.of("Der Name muss 1 bis 100 Zeichen haben."));
        }
        boolean category = "category".equals(body.type());
        if (!category && !"text".equals(body.type())) {
            throw ApiException.badRequest("type muss text oder category sein.");
        }
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL)) {
            throw ApiException.validation(List.of("Dem Bot fehlt das Recht „Kanäle verwalten“."));
        }
        Category parent = null;
        if (!category && body.parentId() != null && !body.parentId().isBlank()) {
            parent = guild.getCategoryById(GuildGuard.snowflake(body.parentId(), "parentId"));
            if (parent == null) {
                throw ApiException.validation(List.of("Die Kategorie existiert nicht."));
            }
        }
        Visibility visibility = visibility(body.visibility());
        List<Role> roles = roles(guild, body.roleIds());
        if (visibility != Visibility.PUBLIC && !ChannelAccess.canManage(guild)) {
            throw ApiException.validation(List.of("Für Kanalrechte braucht der Bot „Rollen verwalten“ und „Kanäle verwalten“."));
        }
        GuildChannel created;
        try {
            created = (category ? guild.createCategory(name) : guild.createTextChannel(name, parent))
                    .reason("Im Dashboard erstellt")
                    .complete();
            ChannelAccess.apply((IPermissionContainer) created, visibility, roles);
        } catch (ErrorResponseException | InsufficientPermissionException e) {
            throw ApiException.validation(List.of("Discord hat das Erstellen abgelehnt: " + e.getMessage()));
        }
        channels.insert(guild.getIdLong(), created.getIdLong(), "dashboard", created.getName());
        audit.record(guild, request.userId(), Source.DASHBOARD, "channels.create",
                category ? "Kategorie „" + created.getName() + "“ erstellt" : "Kanal <#" + created.getId() + "> erstellt",
                Map.of("channelId", created.getId()));
        ctx.status(201).json(GuildDto.Channel.of(created));
    }

    private void setAccess(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Guild guild = request.guild();
        AccessRequest body;
        try {
            body = ctx.bodyAsClass(AccessRequest.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Ungültiger JSON-Body.");
        }
        GuildChannel channel = guild.getGuildChannelById(GuildGuard.snowflake(ctx.pathParam("channelId"), "channelId"));
        if (!(channel instanceof IPermissionContainer container)) {
            throw ApiException.notFound("Den Kanal gibt es nicht.");
        }
        Visibility visibility = visibility(body.visibility());
        List<Role> roles = roles(guild, body.roleIds());
        if (!ChannelAccess.canManage(guild)) {
            throw ApiException.validation(List.of("Für Kanalrechte braucht der Bot „Rollen verwalten“ und „Kanäle verwalten“."));
        }
        try {
            ChannelAccess.apply(container, visibility, roles);
        } catch (ErrorResponseException | InsufficientPermissionException e) {
            throw ApiException.validation(List.of("Discord hat die Rechte für #" + channel.getName() + " abgelehnt: " + e.getMessage()));
        }
        if (visibility != Visibility.PUBLIC) {
            audit.record(guild, request.userId(), Source.DASHBOARD, "channels.access",
                    (visibility == Visibility.PRIVATE ? "Privat gestellt: " : "Nur lesen gestellt: ") + channel.getName(),
                    Map.of("channelId", channel.getId()));
        }
        ctx.status(204);
    }

    private static Visibility visibility(String value) {
        try {
            return value == null ? Visibility.PUBLIC : Visibility.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("visibility muss PUBLIC, READ_ONLY oder PRIVATE sein.");
        }
    }

    private static List<Role> roles(Guild guild, List<String> roleIds) {
        List<Role> roles = new ArrayList<>();
        for (String roleId : roleIds == null ? List.<String>of() : roleIds) {
            Role role = guild.getRoleById(GuildGuard.snowflake(roleId, "roleIds"));
            if (role != null && !role.isPublicRole()) {
                roles.add(role);
            }
        }
        if (roles.size() > 50) {
            throw ApiException.validation(List.of("Es sind höchstens 50 Rollen möglich."));
        }
        return roles;
    }

    private static String cachedName(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getUser().getName();
    }

    private static SettingsDto readBody(Context ctx) {
        try {
            return ctx.bodyAsClass(SettingsDto.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Ungültiger JSON-Body.");
        }
    }
}
