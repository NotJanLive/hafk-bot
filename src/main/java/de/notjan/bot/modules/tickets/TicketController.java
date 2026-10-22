package de.notjan.bot.modules.tickets;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.ApiException;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.GuildGuard.GuildRequest;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.message.MessagePayload;
import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.Ticket.Status;
import de.notjan.bot.modules.tickets.TicketConfig.BlacklistEntry;
import de.notjan.bot.modules.tickets.TicketConfig.ButtonColor;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.FieldOption;
import de.notjan.bot.modules.tickets.TicketConfig.FieldType;
import de.notjan.bot.modules.tickets.TicketConfig.FormField;
import de.notjan.bot.modules.tickets.TicketConfig.Panel;
import de.notjan.bot.modules.tickets.TicketConfig.PanelStyle;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import de.notjan.bot.modules.tickets.TicketConfigService.PanelDraft;
import de.notjan.bot.modules.tickets.TicketRepository.Page;
import de.notjan.bot.modules.tickets.TicketRepository.Query;
import de.notjan.bot.modules.tickets.TicketRepository.Stats;
import io.javalin.http.Context;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;

final class TicketController implements ApiController {

    private static final int PAGE_SIZE = 25;

    record UserDto(String id, String name, String avatarUrl) {

        static UserDto of(Guild guild, Long id, String fallback) {
            if (id == null) {
                return null;
            }
            Member member = guild.getMemberById(id);
            if (member != null) {
                return new UserDto(member.getId(), member.getEffectiveName(), member.getEffectiveAvatarUrl());
            }
            User user = guild.getJDA().getUserById(id);
            return user == null
                    ? new UserDto(String.valueOf(id), fallback, null)
                    : new UserDto(user.getId(), user.getEffectiveName(), user.getEffectiveAvatarUrl());
        }
    }

    record TargetDto(TargetType type, String id, String name, String avatarUrl) {

        static TargetDto of(Guild guild, Target target) {
            if (target.type() == TargetType.ROLE) {
                var role = guild.getRoleById(target.id());
                return new TargetDto(TargetType.ROLE, String.valueOf(target.id()), role == null ? null : role.getName(), null);
            }
            UserDto user = UserDto.of(guild, target.id(), null);
            return new TargetDto(TargetType.USER, user.id(), user.name(), user.avatarUrl());
        }
    }

    record TargetRequest(TargetType type, String id) {

        Target toTarget(String field) {
            if (type == null) {
                throw ApiException.badRequest(field + ": Typ fehlt.");
            }
            return new Target(type, GuildGuard.snowflake(id, field));
        }
    }

    record SettingsDto(String logChannelId, int closeDelaySeconds, int closeRequestHours, boolean creatorCanClose, boolean claimLock,
                       boolean transferToAnyone, boolean notesEnabled, boolean ratingsEnabled, boolean dmOnClose, boolean creatorTranscript,
                       Integer retentionDays, List<TargetDto> staff, List<TargetDto> transcriptViewers) {

        static SettingsDto of(Guild guild, Settings settings) {
            return new SettingsDto(settings.logChannelId() == null ? null : String.valueOf(settings.logChannelId()),
                    settings.closeDelaySeconds(), settings.closeRequestHours(), settings.creatorCanClose(), settings.claimLock(),
                    settings.transferToAnyone(), settings.notesEnabled(), settings.ratingsEnabled(), settings.dmOnClose(),
                    settings.creatorTranscript(), settings.retentionDays(),
                    settings.staff().stream().map(target -> TargetDto.of(guild, target)).toList(),
                    settings.transcriptViewers().stream().map(target -> TargetDto.of(guild, target)).toList());
        }
    }

    record SettingsRequest(String logChannelId, int closeDelaySeconds, int closeRequestHours, boolean creatorCanClose, boolean claimLock,
                           boolean transferToAnyone, boolean notesEnabled, boolean ratingsEnabled, boolean dmOnClose,
                           boolean creatorTranscript, Integer retentionDays, List<TargetRequest> staff,
                           List<TargetRequest> transcriptViewers) {

        Settings toSettings(long guildId) {
            return new Settings(guildId, optionalSnowflake(logChannelId, "logChannelId"), closeDelaySeconds, closeRequestHours,
                    creatorCanClose, claimLock, transferToAnyone, notesEnabled, ratingsEnabled, dmOnClose, creatorTranscript, retentionDays,
                    targets(staff, "staff"), targets(transcriptViewers, "transcriptViewers"));
        }
    }

    record OptionDto(String label, String description, String emoji) {
    }

    record FieldDto(FieldType type, String label, String description, String placeholder, boolean required, Integer minLength,
                    Integer maxLength, Integer minValues, Integer maxValues, List<OptionDto> options) {

        static FieldDto of(FormField field) {
            return new FieldDto(field.type(), field.label(), field.description(), field.placeholder(), field.required(), field.minLength(),
                    field.maxLength(), field.minValues(), field.maxValues(),
                    field.options().stream().map(option -> new OptionDto(option.label(), option.description(), option.emoji())).toList());
        }

        FormField toField() {
            return new FormField(0, type, label, description, placeholder, required, minLength, maxLength, minValues, maxValues,
                    options == null ? List.of() : options.stream()
                            .map(option -> new FieldOption(option.label(), option.description(), option.emoji())).toList());
        }
    }

    record CategoryDto(String id, String name, String emoji, String description, ButtonColor buttonStyle, String parentId,
                       String channelPattern, MessagePayload welcome, String confirmText, String formTitle, int maxOpen,
                       int cooldownMinutes, boolean pingSupport, boolean includeStaff, boolean application, String acceptRoleId,
                       String acceptMessage,
                       String denyMessage, boolean closeOnDecision, int position, List<String> supportRoleIds, List<String> pingRoleIds,
                       List<String> requiredRoleIds, List<FieldDto> fields) {

        static CategoryDto of(Category category) {
            return new CategoryDto(String.valueOf(category.id()), category.name(), category.emoji(), category.description(),
                    category.buttonStyle(), idOf(category.parentId()), category.channelPattern(), category.welcome(), category.confirmText(),
                    category.formTitle(), category.maxOpen(), category.cooldownMinutes(), category.pingSupport(), category.includeStaff(),
                    category.application(),
                    idOf(category.acceptRoleId()), category.acceptMessage(), category.denyMessage(), category.closeOnDecision(),
                    category.position(), ids(category.supportRoleIds()), ids(category.pingRoleIds()), ids(category.requiredRoleIds()),
                    category.fields().stream().map(FieldDto::of).toList());
        }
    }

    record CategoryRequest(String name, String emoji, String description, ButtonColor buttonStyle, String parentId, String channelPattern,
                           MessagePayload welcome, String confirmText, String formTitle, int maxOpen, int cooldownMinutes,
                           boolean pingSupport, boolean includeStaff, boolean application, String acceptRoleId, String acceptMessage,
                           String denyMessage,
                           boolean closeOnDecision, int position, List<String> supportRoleIds, List<String> pingRoleIds,
                           List<String> requiredRoleIds, List<FieldDto> fields) {

        Category toCategory(long id, long guildId) {
            return new Category(id, guildId, name, emoji, description, buttonStyle, optionalSnowflake(parentId, "parentId"), channelPattern,
                    welcome, confirmText, formTitle, maxOpen, cooldownMinutes, pingSupport, includeStaff, application,
                    optionalSnowflake(acceptRoleId, "acceptRoleId"), acceptMessage, denyMessage, closeOnDecision, position,
                    snowflakes(supportRoleIds, "supportRoleIds"), snowflakes(pingRoleIds, "pingRoleIds"),
                    snowflakes(requiredRoleIds, "requiredRoleIds"),
                    fields == null ? List.of() : fields.stream().map(FieldDto::toField).toList());
        }
    }

    record PanelDto(String id, String name, String channelId, String channelName, String messageId, String jumpUrl, PanelStyle style,
                    String placeholder, MessagePayload payload, List<String> categoryIds, Instant updatedAt) {

        static PanelDto of(Guild guild, Panel panel) {
            GuildChannel channel = guild.getGuildChannelById(panel.channelId());
            return new PanelDto(String.valueOf(panel.id()), panel.name(), String.valueOf(panel.channelId()),
                    channel == null ? null : channel.getName(),
                    panel.message() == null ? null : String.valueOf(panel.message().messageId()),
                    panel.message() == null ? null : panel.message().jumpUrl(), panel.style(), panel.placeholder(), panel.payload(),
                    panel.categoryIds().stream().map(String::valueOf).toList(), panel.updatedAt());
        }
    }

    record PanelRequest(String name, String channelId, PanelStyle style, String placeholder, MessagePayload payload, List<String> categoryIds) {

        PanelDraft toDraft() {
            return new PanelDraft(name, GuildGuard.snowflake(channelId, "channelId"), style, placeholder, payload,
                    categoryIds == null ? List.of() : categoryIds.stream().map(id -> GuildGuard.snowflake(id, "categoryIds")).toList());
        }
    }

    record TicketDto(String id, String uuid, int number, String categoryId, String category, String channelId, String channelName,
                     String status, UserDto creator, UserDto openedBy, UserDto claimedBy, UserDto closedBy, Instant createdAt,
                     Instant firstResponseAt, Instant closedAt, String closeReason, String applicationState, List<Answer> answers) {

        static TicketDto of(Guild guild, Ticket ticket) {
            return new TicketDto(String.valueOf(ticket.id()), ticket.uuid(), ticket.number(), idOf(ticket.categoryId()), ticket.categoryName(),
                    ticket.isOpen() ? idOf(ticket.channelId()) : null, ticket.channelName(), ticket.status().name(),
                    UserDto.of(guild, ticket.creatorId(), ticket.creatorName()),
                    ticket.openedBy() == ticket.creatorId() ? null : UserDto.of(guild, ticket.openedBy(), null),
                    UserDto.of(guild, ticket.claimedBy(), null), UserDto.of(guild, ticket.closedBy(), null), ticket.createdAt(),
                    ticket.firstResponseAt(), ticket.closedAt(), ticket.closeReason(),
                    ticket.applicationState() == null ? null : ticket.applicationState().name(), ticket.answers());
        }
    }

    record PageDto(List<TicketDto> tickets, long total, int page, int pageSize) {
    }

    record SupporterDto(UserDto user, int claimed, int closed, int messages, Double ratingAverage, int ratings) {
    }

    record CategoryStatsDto(String categoryId, String name, int total, int open) {
    }

    record DayDto(LocalDate day, int opened, int closed) {
    }

    record StatsDto(int open, int closed, int openedLast7Days, int openedLast30Days, Double averageFirstResponseMinutes,
                    Double averageResolutionMinutes, Double ratingAverage, int ratings, List<CategoryStatsDto> categories,
                    List<SupporterDto> supporters, List<DayDto> days) {

        static StatsDto of(Guild guild, Stats stats) {
            return new StatsDto(stats.open(), stats.closed(), stats.openedLast7Days(), stats.openedLast30Days(),
                    stats.averageFirstResponseMinutes(), stats.averageResolutionMinutes(), stats.ratingAverage(), stats.ratings(),
                    stats.categories().stream().map(category -> new CategoryStatsDto(idOf(category.categoryId()), category.name(),
                            category.total(), category.open())).toList(),
                    stats.supporters().stream().limit(50).map(supporter -> new SupporterDto(UserDto.of(guild, supporter.userId(), null),
                            supporter.claimed(), supporter.closed(), supporter.messages(),
                            supporter.ratings() == 0 ? null : supporter.ratingAverage(), supporter.ratings())).toList(),
                    stats.days().stream().map(day -> new DayDto(day.day(), day.opened(), day.closed())).toList());
        }
    }

    record BlacklistDto(TargetDto target, String reason, Instant expiresAt, UserDto createdBy, Instant createdAt) {
    }

    record BlacklistRequest(TargetType type, String id, String reason, Integer days) {
    }

    record CloseRequest(String reason) {
    }

    private final GuildGuard guard;
    private final TicketService tickets;
    private final TicketConfigService config;
    private final TicketRepository repository;

    TicketController(GuildGuard guard, TicketService tickets, TicketConfigService config, TicketRepository repository) {
        this.guard = guard;
        this.tickets = tickets;
        this.config = config;
        this.repository = repository;
    }

    @Override
    public void addEndpoints() {
        get("/guilds/{guildId}/tickets", this::list);
        get("/guilds/{guildId}/tickets/stats", this::stats);
        get("/guilds/{guildId}/tickets/settings", this::getSettings);
        put("/guilds/{guildId}/tickets/settings", this::putSettings);
        get("/guilds/{guildId}/tickets/categories", this::categories);
        post("/guilds/{guildId}/tickets/categories", this::createCategory);
        get("/guilds/{guildId}/tickets/categories/{id}", this::getCategory);
        put("/guilds/{guildId}/tickets/categories/{id}", this::updateCategory);
        delete("/guilds/{guildId}/tickets/categories/{id}", this::deleteCategory);
        get("/guilds/{guildId}/tickets/panels", this::panels);
        post("/guilds/{guildId}/tickets/panels", this::createPanel);
        get("/guilds/{guildId}/tickets/panels/{id}", this::getPanel);
        put("/guilds/{guildId}/tickets/panels/{id}", this::updatePanel);
        delete("/guilds/{guildId}/tickets/panels/{id}", this::deletePanel);
        get("/guilds/{guildId}/tickets/blacklist", this::blacklist);
        post("/guilds/{guildId}/tickets/blacklist", this::addBlacklist);
        delete("/guilds/{guildId}/tickets/blacklist/{type}/{targetId}", this::removeBlacklist);
        post("/guilds/{guildId}/tickets/{id}/close", this::close);
    }

    private void list(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Status status = switch (ctx.queryParam("status") == null ? "" : ctx.queryParam("status")) {
            case "OPEN" -> Status.OPEN;
            case "CLOSED" -> Status.CLOSED;
            default -> null;
        };
        String search = ctx.queryParam("search");
        String userId = ctx.queryParam("userId");
        String categoryId = ctx.queryParam("categoryId");
        int page = parsePage(ctx.queryParam("page"));
        Page result = repository.list(request.guild().getIdLong(), new Query(status,
                search == null || search.isBlank() ? null : search.strip(),
                userId == null || userId.isBlank() ? null : GuildGuard.snowflake(userId, "userId"),
                categoryId == null || categoryId.isBlank() ? null : parseId(categoryId),
                PAGE_SIZE, page * PAGE_SIZE));
        ctx.json(new PageDto(result.tickets().stream().map(ticket -> TicketDto.of(request.guild(), ticket)).toList(), result.total(), page,
                PAGE_SIZE));
    }

    private void stats(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(StatsDto.of(request.guild(), repository.stats(request.guild().getIdLong())));
    }

    private void getSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(SettingsDto.of(request.guild(), config.settings(request.guild().getIdLong())));
    }

    private void putSettings(Context ctx) {
        GuildRequest request = guard.require(ctx);
        SettingsRequest body = ApiController.readBody(ctx, SettingsRequest.class);
        Settings updated = config.updateSettings(request.guild(), request.userId(), body.toSettings(request.guild().getIdLong()));
        ctx.json(SettingsDto.of(request.guild(), updated));
    }

    private void categories(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(config.categories(request.guild().getIdLong()).stream().map(CategoryDto::of).toList());
    }

    private void getCategory(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(CategoryDto.of(config.category(request.guild(), ApiController.longParam(ctx, "id"))));
    }

    private void createCategory(Context ctx) {
        GuildRequest request = guard.require(ctx);
        CategoryRequest body = ApiController.readBody(ctx, CategoryRequest.class);
        Category saved = config.saveCategory(request.guild(), request.userId(), body.toCategory(0, request.guild().getIdLong()));
        ctx.status(201).json(CategoryDto.of(saved));
    }

    private void updateCategory(Context ctx) {
        GuildRequest request = guard.require(ctx);
        CategoryRequest body = ApiController.readBody(ctx, CategoryRequest.class);
        Category saved = config.saveCategory(request.guild(), request.userId(),
                body.toCategory(ApiController.longParam(ctx, "id"), request.guild().getIdLong()));
        ctx.json(CategoryDto.of(saved));
    }

    private void deleteCategory(Context ctx) {
        GuildRequest request = guard.require(ctx);
        config.deleteCategory(request.guild(), request.userId(), ApiController.longParam(ctx, "id"));
        ctx.status(204);
    }

    private void panels(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(config.panels(request.guild().getIdLong()).stream().map(panel -> PanelDto.of(request.guild(), panel)).toList());
    }

    private void getPanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        ctx.json(PanelDto.of(request.guild(), config.panel(request.guild(), ApiController.longParam(ctx, "id"))));
    }

    private void createPanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        PanelRequest body = ApiController.readBody(ctx, PanelRequest.class);
        Panel panel = config.savePanel(request.guild(), request.userId(), 0, body.toDraft());
        ctx.status(201).json(PanelDto.of(request.guild(), panel));
    }

    private void updatePanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        PanelRequest body = ApiController.readBody(ctx, PanelRequest.class);
        Panel panel = config.savePanel(request.guild(), request.userId(), ApiController.longParam(ctx, "id"), body.toDraft());
        ctx.json(PanelDto.of(request.guild(), panel));
    }

    private void deletePanel(Context ctx) {
        GuildRequest request = guard.require(ctx);
        config.deletePanel(request.guild(), request.userId(), ApiController.longParam(ctx, "id"),
                Boolean.parseBoolean(ctx.queryParam("deleteMessage")));
        ctx.status(204);
    }

    private void blacklist(Context ctx) {
        GuildRequest request = guard.require(ctx);
        Guild guild = request.guild();
        ctx.json(tickets.blacklist(guild.getIdLong()).stream().map(entry -> blacklistDto(guild, entry)).toList());
    }

    private void addBlacklist(Context ctx) {
        GuildRequest request = guard.require(ctx);
        BlacklistRequest body = ApiController.readBody(ctx, BlacklistRequest.class);
        Target target = new TargetRequest(body.type(), body.id()).toTarget("id");
        tickets.blacklistAdd(request.guild(), request.userId(), Source.DASHBOARD, target, body.reason(), body.days());
        ctx.status(201).json(tickets.blacklist(request.guild().getIdLong()).stream().map(entry -> blacklistDto(request.guild(), entry)).toList());
    }

    private void removeBlacklist(Context ctx) {
        GuildRequest request = guard.require(ctx);
        TargetType type;
        try {
            type = TargetType.valueOf(ctx.pathParam("type"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type ist ungültig.");
        }
        tickets.blacklistRemove(request.guild(), request.userId(), Source.DASHBOARD,
                new Target(type, GuildGuard.snowflake(ctx.pathParam("targetId"), "targetId")));
        ctx.status(204);
    }

    private void close(Context ctx) {
        GuildRequest request = guard.require(ctx);
        CloseRequest body = ApiController.readBody(ctx, CloseRequest.class);
        Ticket ticket = tickets.requireOpen(request.guild(), ApiController.longParam(ctx, "id"));
        tickets.close(request.guild(), ticket, request.userId(), body.reason());
        ctx.status(204);
    }

    private static BlacklistDto blacklistDto(Guild guild, BlacklistEntry entry) {
        return new BlacklistDto(TargetDto.of(guild, new Target(entry.type(), entry.targetId())), entry.reason(), entry.expiresAt(),
                UserDto.of(guild, entry.createdBy(), null), entry.createdAt());
    }

    private static int parsePage(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(10_000, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("page ist ungültig.");
        }
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("categoryId ist ungültig.");
        }
    }

    private static Long optionalSnowflake(String value, String field) {
        return value == null || value.isBlank() ? null : GuildGuard.snowflake(value, field);
    }

    private static Set<Long> snowflakes(List<String> values, String field) {
        return values == null ? Set.of() : values.stream().map(value -> GuildGuard.snowflake(value, field)).collect(Collectors.toSet());
    }

    private static Set<Target> targets(List<TargetRequest> values, String field) {
        return values == null ? Set.of() : values.stream().map(value -> value.toTarget(field)).collect(Collectors.toSet());
    }

    private static List<String> ids(Set<Long> values) {
        return values.stream().map(String::valueOf).sorted().toList();
    }

    private static String idOf(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
