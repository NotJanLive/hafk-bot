package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiException;
import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import de.notjan.bot.modules.tickets.TicketRepository.Rating;
import de.notjan.bot.modules.tickets.TranscriptRepository.Asset;
import de.notjan.bot.modules.tickets.TranscriptRepository.StoredMessage;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TranscriptService {

    enum Viewer {
        MANAGER,
        STAFF,
        CREATOR
    }

    record UserDto(String id, String name, String avatarUrl, Integer color, boolean bot) {
    }

    record GuildDto(String id, String name, String iconUrl) {
    }

    record EventDto(String type, String actorId, String targetId, String detail, Instant createdAt) {
    }

    record RatingDto(int stars, String comment, String supporterId, Instant createdAt) {
    }

    record TicketDto(String id, String uuid, int number, String category, String channelName, String status, String creatorId,
                     String openedBy, String claimedBy, String closedBy, Instant createdAt, Instant firstResponseAt, Instant closedAt,
                     String closeReason, String applicationState, List<Answer> answers, RatingDto rating) {
    }

    record RoleDto(String name, Integer color) {
    }

    record TranscriptDto(GuildDto guild, TicketDto ticket, Map<String, UserDto> users, Map<String, RoleDto> roles,
                         Map<String, String> channels, List<EventDto> events, List<JsonNode> messages, String viewer, int hiddenInternal) {
    }

    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{17,20})>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d{17,20})>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d{17,20})>");
    private static final int MAX_LOOKUPS = 50;

    record AssetFile(Path path, String contentType, String fileName) {
    }

    private final JDA jda;
    private final AccessService access;
    private final TicketRepository tickets;
    private final TranscriptRepository transcripts;
    private final TicketConfigRepository config;
    private final AssetStore assets;
    private final ObjectMapper json;

    TranscriptService(JDA jda, AccessService access, TicketRepository tickets, TranscriptRepository transcripts,
                      TicketConfigRepository config, AssetStore assets, ObjectMapper json) {
        this.jda = jda;
        this.access = access;
        this.tickets = tickets;
        this.transcripts = transcripts;
        this.config = config;
        this.assets = assets;
        this.json = json;
    }

    TranscriptDto transcript(String uuid, long userId) {
        Ticket ticket = find(uuid);
        Guild guild = guild(ticket);
        Viewer viewer = requireViewer(guild, ticket, userId);
        List<JsonNode> messages = new ArrayList<>();
        Map<String, UserDto> users = new LinkedHashMap<>();
        int hidden = 0;
        StringBuilder mentionSource = new StringBuilder();
        ticket.answers().forEach(answer -> mentionSource.append(answer.answer()).append('\n'));
        for (StoredMessage stored : transcripts.messages(ticket.id())) {
            if (stored.internal() && viewer == Viewer.CREATOR) {
                hidden++;
                continue;
            }
            try {
                ObjectNode node = (ObjectNode) json.readTree(stored.data());
                mentionSource.append(stored.data()).append('\n');
                node.put("internal", stored.internal());
                node.put("deletedAt", stored.deletedAt() == null ? null : stored.deletedAt().toString());
                JsonNode author = node.path("author");
                if (author.has("id") && !users.containsKey(author.get("id").asText())) {
                    users.put(author.get("id").asText(), new UserDto(author.get("id").asText(), author.path("displayName").asText(null),
                            author.path("avatarUrl").asText(null), author.has("color") ? author.get("color").asInt() : null,
                            author.path("bot").asBoolean(false)));
                }
                messages.add(node);
            } catch (Exception e) {
                hidden++;
            }
        }
        Set<Long> referenced = new LinkedHashSet<>(List.of(ticket.creatorId(), ticket.openedBy()));
        if (ticket.claimedBy() != null) {
            referenced.add(ticket.claimedBy());
        }
        if (ticket.closedBy() != null) {
            referenced.add(ticket.closedBy());
        }
        List<EventDto> events = tickets.events(ticket.id()).stream().map(event -> {
            referenced.add(event.actorId());
            if (event.targetId() != null && !"ROLE".equals(event.detail())) {
                referenced.add(event.targetId());
            }
            return new EventDto(event.type().name(), String.valueOf(event.actorId()),
                    event.targetId() == null ? null : String.valueOf(event.targetId()), event.detail(), event.createdAt());
        }).toList();
        Rating rating = tickets.rating(ticket.id()).orElse(null);
        if (rating != null && rating.supporterId() != null) {
            referenced.add(rating.supporterId());
        }
        Matcher userMatcher = USER_MENTION.matcher(mentionSource);
        while (userMatcher.find()) {
            referenced.add(Long.parseLong(userMatcher.group(1)));
        }
        int[] lookups = {0};
        for (long id : referenced) {
            users.computeIfAbsent(String.valueOf(id), key -> user(guild, id, ticket, lookups));
        }
        Map<String, RoleDto> roles = new LinkedHashMap<>();
        Matcher roleMatcher = ROLE_MENTION.matcher(mentionSource);
        while (roleMatcher.find()) {
            Role role = guild.getRoleById(roleMatcher.group(1));
            if (role != null) {
                int color = role.getColors().getPrimaryRaw();
                roles.putIfAbsent(role.getId(), new RoleDto(role.getName(), color == Role.DEFAULT_COLOR_RAW ? null : color));
            }
        }
        Map<String, String> channels = new LinkedHashMap<>();
        Matcher channelMatcher = CHANNEL_MENTION.matcher(mentionSource);
        while (channelMatcher.find()) {
            GuildChannel channel = guild.getGuildChannelById(channelMatcher.group(1));
            if (channel != null) {
                channels.putIfAbsent(channel.getId(), channel.getName());
            }
        }
        if (ticket.channelId() != null) {
            channels.putIfAbsent(String.valueOf(ticket.channelId()), ticket.channelName());
        }
        TicketDto dto = new TicketDto(String.valueOf(ticket.id()), ticket.uuid(), ticket.number(), ticket.categoryName(), ticket.channelName(),
                ticket.status().name(), String.valueOf(ticket.creatorId()), String.valueOf(ticket.openedBy()), id(ticket.claimedBy()),
                id(ticket.closedBy()), ticket.createdAt(), ticket.firstResponseAt(), ticket.closedAt(), ticket.closeReason(),
                ticket.applicationState() == null ? null : ticket.applicationState().name(), ticket.answers(),
                rating == null ? null : new RatingDto(rating.stars(), viewer == Viewer.CREATOR ? null : rating.comment(),
                        id(rating.supporterId()), rating.createdAt()));
        return new TranscriptDto(new GuildDto(guild.getId(), guild.getName(), guild.getIconUrl()), dto, users, roles, channels, events,
                messages, viewer.name(), hidden);
    }

    AssetFile asset(String uuid, long assetId, long userId) {
        Ticket ticket = find(uuid);
        requireViewer(guild(ticket), ticket, userId);
        Asset asset = transcripts.asset(assetId).filter(candidate -> candidate.ticketId() == ticket.id())
                .orElseThrow(() -> UserFacingException.notFound("Diese Datei gibt es nicht."));
        Path path = assets.resolve(asset).orElseThrow(() -> UserFacingException.notFound("Diese Datei wurde nicht gespeichert."));
        return new AssetFile(path, asset.contentType() == null ? "application/octet-stream" : asset.contentType(), asset.fileName());
    }

    boolean canView(Guild guild, long userId) {
        Member member = member(guild, userId).orElse(null);
        return member != null && viewerRole(guild, member, null) != null;
    }

    private Viewer requireViewer(Guild guild, Ticket ticket, long userId) {
        Member member = member(guild, userId).orElse(null);
        Viewer viewer = member == null ? null : viewerRole(guild, member, ticket);
        if (viewer == null && member == null && ticket.creatorId() == userId && config.settings(guild.getIdLong()).creatorTranscript()) {
            viewer = Viewer.CREATOR;
        }
        if (viewer == null) {
            throw ApiException.forbidden("Du hast keinen Zugriff auf dieses Transkript.");
        }
        return viewer;
    }

    private Viewer viewerRole(Guild guild, Member member, Ticket ticket) {
        if (access.check(guild, member.getIdLong()).isPresent()) {
            return Viewer.MANAGER;
        }
        Settings settings = config.settings(guild.getIdLong());
        Set<Long> roles = new java.util.HashSet<>(member.getRoles().stream().map(ISnowflake::getIdLong).toList());
        boolean granted = settings.transcriptViewers().stream().anyMatch(target -> target.type() == TargetType.USER
                ? target.id() == member.getIdLong()
                : roles.contains(target.id()));
        if (granted) {
            return Viewer.STAFF;
        }
        if (ticket != null && ticket.categoryId() != null && config.category(ticket.categoryId())
                .map(category -> category.supportRoleIds().stream().anyMatch(roles::contains))
                .orElse(false)) {
            return Viewer.STAFF;
        }
        if (ticket != null && ticket.creatorId() == member.getIdLong() && settings.creatorTranscript()) {
            return Viewer.CREATOR;
        }
        return null;
    }

    private Ticket find(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-f-]{36}")) {
            throw UserFacingException.notFound("Dieses Transkript gibt es nicht.");
        }
        return tickets.findByUuid(uuid).orElseThrow(() -> UserFacingException.notFound("Dieses Transkript gibt es nicht."));
    }

    private Guild guild(Ticket ticket) {
        Guild guild = jda.getGuildById(ticket.guildId());
        if (guild == null) {
            throw UserFacingException.notFound("Der Bot ist nicht mehr auf dem Server dieses Tickets.");
        }
        return guild;
    }

    private UserDto user(Guild guild, long id, Ticket ticket, int[] lookups) {
        Member member = guild.getMemberById(id);
        if (member != null) {
            int color = member.getColors().getPrimaryRaw();
            return new UserDto(member.getId(), member.getEffectiveName(), member.getEffectiveAvatarUrl(),
                    color == Role.DEFAULT_COLOR_RAW ? null : color, member.getUser().isBot());
        }
        User user = jda.getUserById(id);
        if (user == null && lookups[0] < MAX_LOOKUPS) {
            lookups[0]++;
            try {
                user = jda.retrieveUserById(id).complete();
            } catch (ErrorResponseException e) {
                user = null;
            }
        }
        if (user != null) {
            return new UserDto(user.getId(), user.getEffectiveName(), user.getEffectiveAvatarUrl(), null, user.isBot());
        }
        return new UserDto(String.valueOf(id), id == ticket.creatorId() ? ticket.creatorName() : null, null, null, false);
    }

    private static Optional<Member> member(Guild guild, long userId) {
        Member cached = guild.getMemberById(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return Optional.of(guild.retrieveMemberById(userId).complete());
        } catch (ErrorResponseException e) {
            return Optional.empty();
        }
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
