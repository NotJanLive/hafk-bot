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
import de.notjan.bot.guild.GuildSettings;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.guild.GuildSettingsValidator.InvalidSettingsException;
import de.notjan.bot.guild.SettingsUpdater;
import io.javalin.http.Context;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.put;

public final class GuildController implements ApiController {

    private static final int DEFAULT_AUDIT_LIMIT = 50;
    private static final int MAX_AUDIT_LIMIT = 200;

    private final GuildGuard guard;
    private final GuildSettingsService settings;
    private final SettingsUpdater updater;
    private final AuditLogService audit;

    public GuildController(GuildGuard guard, GuildSettingsService settings, SettingsUpdater updater, AuditLogService audit) {
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

        Long logChannelId = body.logChannelId() == null ? null : GuildGuard.snowflake(body.logChannelId(), "logChannelId");
        Set<Long> roleIds = (body.dashboardRoleIds() == null ? List.<String>of() : body.dashboardRoleIds()).stream()
                .map(id -> GuildGuard.snowflake(id, "dashboardRoleIds"))
                .collect(Collectors.toSet());

        try {
            GuildSettings updated = updater.apply(request.guild(), request.userId(), Source.DASHBOARD, current -> current
                    .withLogChannel(logChannelId)
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
        ctx.json(audit.recent(request.guild().getIdLong(), limit).stream().map(AuditEntryDto::of).toList());
    }

    private static SettingsDto readBody(Context ctx) {
        try {
            return ctx.bodyAsClass(SettingsDto.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Ungültiger JSON-Body.");
        }
    }
}
