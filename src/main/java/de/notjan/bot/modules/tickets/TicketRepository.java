package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.Ticket.ApplicationState;
import de.notjan.bot.modules.tickets.Ticket.Event;
import de.notjan.bot.modules.tickets.Ticket.EventType;
import de.notjan.bot.modules.tickets.Ticket.Member;
import de.notjan.bot.modules.tickets.Ticket.Status;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TicketRepository {

    record NewTicket(String uuid, long guildId, int number, long categoryId, String categoryName, long channelId, String channelName,
                     List<Answer> answers, long creatorId, String creatorName, long openedBy, ApplicationState applicationState) {
    }

    record ChannelRef(long ticketId, long guildId, long channelId, Long notesThreadId) {
    }

    record Rating(long ticketId, long userId, Long supporterId, int stars, String comment, Instant createdAt) {
    }

    record Query(Status status, String search, Long userId, Long categoryId, int limit, int offset) {
    }

    record Page(List<Ticket> tickets, long total) {
    }

    record SupporterStats(long userId, int claimed, int closed, int messages, double ratingAverage, int ratings) {
    }

    record CategoryStats(Long categoryId, String name, int total, int open) {
    }

    record DayStats(LocalDate day, int opened, int closed) {
    }

    record Stats(int open, int closed, int openedLast7Days, int openedLast30Days, Double averageFirstResponseMinutes,
                 Double averageResolutionMinutes, Double ratingAverage, int ratings, List<CategoryStats> categories,
                 List<SupporterStats> supporters, List<DayStats> days) {
    }

    record Expired(long id, long guildId, String uuid) {
    }

    private static final TypeReference<List<Answer>> ANSWERS = new TypeReference<>() {
    };

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final RowMapper<Ticket> mapper;

    public TicketRepository(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.mapper = (rs, ctx) -> new Ticket(
                rs.getLong("id"),
                rs.getString("uuid"),
                rs.getLong("guild_id"),
                rs.getInt("number"),
                rs.getObject("category_id", Long.class),
                rs.getString("category_name"),
                rs.getObject("channel_id", Long.class),
                rs.getString("channel_name"),
                rs.getObject("notes_thread_id", Long.class),
                rs.getObject("control_message_id", Long.class),
                rs.getObject("answers_message_id", Long.class),
                readAnswers(rs.getString("answers")),
                rs.getLong("creator_id"),
                rs.getString("creator_name"),
                rs.getLong("opened_by"),
                rs.getObject("claimed_by", Long.class),
                Status.valueOf(rs.getString("status")),
                rs.getString("application_state") == null ? null : ApplicationState.valueOf(rs.getString("application_state")),
                rs.getObject("close_request_by", Long.class),
                rs.getString("close_request_reason"),
                instant(rs.getTimestamp("close_request_until")),
                rs.getObject("close_request_message_id", Long.class),
                instant(rs.getTimestamp("first_response_at")),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("closed_at")),
                rs.getObject("closed_by", Long.class),
                rs.getString("close_reason"));
    }

    long insert(NewTicket ticket) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        INSERT INTO tickets (uuid, guild_id, number, category_id, category_name, channel_id, channel_name, answers,
                                             creator_id, creator_name, opened_by, status, application_state)
                        VALUES (:uuid, :guildId, :number, :categoryId, :categoryName, :channelId, :channelName, :answers, :creatorId,
                                :creatorName, :openedBy, 'OPEN', :applicationState)""")
                .bind("uuid", ticket.uuid())
                .bind("guildId", ticket.guildId())
                .bind("number", ticket.number())
                .bind("categoryId", ticket.categoryId())
                .bind("categoryName", ticket.categoryName())
                .bind("channelId", ticket.channelId())
                .bind("channelName", ticket.channelName())
                .bind("answers", ticket.answers().isEmpty() ? null : write(ticket.answers()))
                .bind("creatorId", ticket.creatorId())
                .bind("creatorName", ticket.creatorName())
                .bind("openedBy", ticket.openedBy())
                .bind("applicationState", ticket.applicationState() == null ? null : ticket.applicationState().name())
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one());
    }

    Optional<Ticket> find(long id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM tickets WHERE id = :id").bind("id", id).map(mapper).findOne());
    }

    Optional<Ticket> findByUuid(String uuid) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM tickets WHERE uuid = :uuid").bind("uuid", uuid).map(mapper).findOne());
    }

    Optional<Ticket> findOpenByChannel(long channelId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT * FROM tickets WHERE (channel_id = :channelId OR notes_thread_id = :channelId) AND status = 'OPEN'""")
                .bind("channelId", channelId)
                .map(mapper)
                .findOne());
    }

    List<ChannelRef> openChannels() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, guild_id, channel_id, notes_thread_id FROM tickets WHERE status = 'OPEN' AND channel_id IS NOT NULL""")
                .map((rs, ctx) -> new ChannelRef(rs.getLong("id"), rs.getLong("guild_id"), rs.getLong("channel_id"),
                        rs.getObject("notes_thread_id", Long.class)))
                .list());
    }

    List<Ticket> openTickets(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM tickets WHERE guild_id = :guildId AND status = 'OPEN' ORDER BY id")
                .bind("guildId", guildId)
                .map(mapper)
                .list());
    }

    int countOpenByCreator(long guildId, long creatorId, long categoryId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT COUNT(*) FROM tickets WHERE guild_id = :guildId AND creator_id = :creatorId AND category_id = :categoryId
                        AND status = 'OPEN'""")
                .bind("guildId", guildId)
                .bind("creatorId", creatorId)
                .bind("categoryId", categoryId)
                .mapTo(Integer.class)
                .one());
    }

    Optional<Instant> lastCreated(long guildId, long creatorId, long categoryId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT MAX(created_at) FROM tickets WHERE guild_id = :guildId AND creator_id = :creatorId AND category_id = :categoryId""")
                .bind("guildId", guildId)
                .bind("creatorId", creatorId)
                .bind("categoryId", categoryId)
                .mapTo(Timestamp.class)
                .findOne()
                .map(Timestamp::toInstant));
    }

    void setControlMessage(long id, long messageId) {
        update(id, "control_message_id = :value", messageId);
    }

    void setAnswersMessage(long id, long messageId) {
        update(id, "answers_message_id = :value", messageId);
    }

    void setNotesThread(long id, long threadId) {
        update(id, "notes_thread_id = :value", threadId);
    }

    void setClaimedBy(long id, Long userId) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE tickets SET claimed_by = :userId WHERE id = :id")
                .bind("id", id)
                .bind("userId", userId)
                .execute());
    }

    void setChannelName(long id, String name) {
        update(id, "channel_name = :value", name);
    }

    void setApplicationState(long id, ApplicationState state) {
        update(id, "application_state = :value", state.name());
    }

    void setCategory(long id, long categoryId, String categoryName) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE tickets SET category_id = :categoryId, category_name = :name WHERE id = :id")
                .bind("id", id)
                .bind("categoryId", categoryId)
                .bind("name", categoryName)
                .execute());
    }

    void setFirstResponse(long id, Instant at) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE tickets SET first_response_at = :at WHERE id = :id AND first_response_at IS NULL")
                .bind("id", id)
                .bind("at", Timestamp.from(at))
                .execute());
    }

    void setCloseRequest(long id, long requestedBy, String reason, Instant until, long messageId) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE tickets SET close_request_by = :by, close_request_reason = :reason, close_request_until = :until,
                            close_request_message_id = :messageId WHERE id = :id""")
                .bind("id", id)
                .bind("by", requestedBy)
                .bind("reason", reason)
                .bind("until", Timestamp.from(until))
                .bind("messageId", messageId)
                .execute());
    }

    void clearCloseRequest(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        UPDATE tickets SET close_request_by = NULL, close_request_reason = NULL, close_request_until = NULL,
                            close_request_message_id = NULL WHERE id = :id""")
                .bind("id", id)
                .execute());
    }

    List<Long> dueCloseRequests(Instant now) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id FROM tickets WHERE status = 'OPEN' AND close_request_until IS NOT NULL AND close_request_until <= :now""")
                .bind("now", Timestamp.from(now))
                .mapTo(Long.class)
                .list());
    }

    boolean close(long id, long closedBy, String reason, Instant closedAt) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        UPDATE tickets SET status = 'CLOSED', closed_by = :closedBy, close_reason = :reason, closed_at = :closedAt,
                            close_request_until = NULL WHERE id = :id AND status = 'OPEN'""")
                .bind("id", id)
                .bind("closedBy", closedBy)
                .bind("reason", reason)
                .bind("closedAt", Timestamp.from(closedAt))
                .execute() > 0);
    }

    void addMember(long ticketId, Target target, long addedBy) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT IGNORE INTO ticket_members (ticket_id, target_type, target_id, added_by)
                        VALUES (:ticketId, :type, :targetId, :addedBy)""")
                .bind("ticketId", ticketId)
                .bind("type", target.type().name())
                .bind("targetId", target.id())
                .bind("addedBy", addedBy)
                .execute());
    }

    boolean removeMember(long ticketId, Target target) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        DELETE FROM ticket_members WHERE ticket_id = :ticketId AND target_type = :type AND target_id = :targetId""")
                .bind("ticketId", ticketId)
                .bind("type", target.type().name())
                .bind("targetId", target.id())
                .execute() > 0);
    }

    List<Member> members(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_members WHERE ticket_id = :ticketId ORDER BY created_at")
                .bind("ticketId", ticketId)
                .map((rs, ctx) -> new Member(TargetType.valueOf(rs.getString("target_type")), rs.getLong("target_id"),
                        rs.getLong("added_by"), rs.getTimestamp("created_at").toInstant()))
                .list());
    }

    void event(long ticketId, EventType type, long actorId, Long targetId, String detail) {
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO ticket_events (ticket_id, type, actor_id, target_id, detail) VALUES (:ticketId, :type, :actorId, :targetId, :detail)""")
                .bind("ticketId", ticketId)
                .bind("type", type.name())
                .bind("actorId", actorId)
                .bind("targetId", targetId)
                .bind("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 999) + "…")
                .execute());
    }

    List<Event> events(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_events WHERE ticket_id = :ticketId ORDER BY created_at, id")
                .bind("ticketId", ticketId)
                .map((rs, ctx) -> new Event(rs.getLong("id"), EventType.valueOf(rs.getString("type")), rs.getLong("actor_id"),
                        rs.getObject("target_id", Long.class), rs.getString("detail"), rs.getTimestamp("created_at").toInstant()))
                .list());
    }

    boolean saveRating(long ticketId, long guildId, long userId, Long supporterId, int stars, String comment) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        INSERT IGNORE INTO ticket_ratings (ticket_id, guild_id, user_id, supporter_id, stars, comment)
                        VALUES (:ticketId, :guildId, :userId, :supporterId, :stars, :comment)""")
                .bind("ticketId", ticketId)
                .bind("guildId", guildId)
                .bind("userId", userId)
                .bind("supporterId", supporterId)
                .bind("stars", stars)
                .bind("comment", comment)
                .execute() > 0);
    }

    Optional<Rating> rating(long ticketId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT * FROM ticket_ratings WHERE ticket_id = :ticketId")
                .bind("ticketId", ticketId)
                .map((rs, ctx) -> new Rating(rs.getLong("ticket_id"), rs.getLong("user_id"), rs.getObject("supporter_id", Long.class),
                        rs.getInt("stars"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant()))
                .findOne());
    }

    Optional<Long> mostActiveStaff(long ticketId, long creatorId, long botId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT author_id FROM ticket_messages WHERE ticket_id = :ticketId AND author_id NOT IN (:creatorId, :botId)
                        AND internal = FALSE GROUP BY author_id ORDER BY COUNT(*) DESC LIMIT 1""")
                .bind("ticketId", ticketId)
                .bind("creatorId", creatorId)
                .bind("botId", botId)
                .mapTo(Long.class)
                .findOne());
    }

    Page list(long guildId, Query query) {
        StringBuilder where = new StringBuilder("WHERE t.guild_id = :guildId");
        if (query.status() != null) {
            where.append(" AND t.status = :status");
        }
        if (query.userId() != null) {
            where.append(" AND (t.creator_id = :userId OR t.claimed_by = :userId OR t.closed_by = :userId)");
        }
        if (query.categoryId() != null) {
            where.append(" AND t.category_id = :categoryId");
        }
        if (query.search() != null) {
            where.append(" AND (t.creator_name LIKE :search OR t.channel_name LIKE :search OR t.category_name LIKE :search")
                    .append(" OR CAST(t.number AS CHAR) = :exact OR t.close_reason LIKE :search)");
        }
        return jdbi.withHandle(handle -> {
            var select = handle.createQuery("SELECT t.* FROM tickets t " + where + " ORDER BY t.id DESC LIMIT :limit OFFSET :offset");
            var count = handle.createQuery("SELECT COUNT(*) FROM tickets t " + where);
            for (var statement : List.of(select, count)) {
                statement.bind("guildId", guildId);
                if (query.status() != null) {
                    statement.bind("status", query.status().name());
                }
                if (query.userId() != null) {
                    statement.bind("userId", query.userId());
                }
                if (query.categoryId() != null) {
                    statement.bind("categoryId", query.categoryId());
                }
                if (query.search() != null) {
                    statement.bind("search", "%" + query.search().replace("%", "\\%").replace("_", "\\_") + "%");
                    statement.bind("exact", query.search().replace("#", ""));
                }
            }
            List<Ticket> tickets = select.bind("limit", query.limit()).bind("offset", query.offset()).map(mapper).list();
            return new Page(tickets, count.mapTo(Long.class).one());
        });
    }

    Stats stats(long guildId) {
        return jdbi.withHandle(handle -> {
            Instant now = Instant.now();
            var totals = handle.createQuery("""
                            SELECT SUM(status = 'OPEN') AS open, SUM(status = 'CLOSED') AS closed,
                                   SUM(created_at >= :week) AS week, SUM(created_at >= :month) AS month,
                                   AVG(TIMESTAMPDIFF(SECOND, created_at, first_response_at)) / 60 AS first_response,
                                   AVG(CASE WHEN status = 'CLOSED' THEN TIMESTAMPDIFF(SECOND, created_at, closed_at) END) / 60 AS resolution
                            FROM tickets WHERE guild_id = :guildId""")
                    .bind("guildId", guildId)
                    .bind("week", Timestamp.from(now.minusSeconds(7 * 86_400)))
                    .bind("month", Timestamp.from(now.minusSeconds(30 * 86_400)))
                    .map((rs, ctx) -> new Object[]{rs.getInt("open"), rs.getInt("closed"), rs.getInt("week"), rs.getInt("month"),
                            rs.getObject("first_response") == null ? null : rs.getDouble("first_response"),
                            rs.getObject("resolution") == null ? null : rs.getDouble("resolution")})
                    .one();
            var ratingTotals = handle.createQuery("SELECT AVG(stars) AS average, COUNT(*) AS total FROM ticket_ratings WHERE guild_id = :guildId")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> new Object[]{rs.getObject("average") == null ? null : rs.getDouble("average"), rs.getInt("total")})
                    .one();
            List<CategoryStats> categories = handle.createQuery("""
                            SELECT category_id, category_name, COUNT(*) AS total, SUM(status = 'OPEN') AS open
                            FROM tickets WHERE guild_id = :guildId GROUP BY category_id, category_name ORDER BY total DESC""")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> new CategoryStats(rs.getObject("category_id", Long.class), rs.getString("category_name"),
                            rs.getInt("total"), rs.getInt("open")))
                    .list();
            Map<Long, int[]> counts = new LinkedHashMap<>();
            handle.createQuery("""
                            SELECT claimed_by AS user_id, COUNT(*) AS total FROM tickets WHERE guild_id = :guildId AND claimed_by IS NOT NULL
                            GROUP BY claimed_by""")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> Map.entry(rs.getLong("user_id"), rs.getInt("total")))
                    .forEach(entry -> counts.computeIfAbsent(entry.getKey(), key -> new int[3])[0] = entry.getValue());
            handle.createQuery("""
                            SELECT closed_by AS user_id, COUNT(*) AS total FROM tickets WHERE guild_id = :guildId AND status = 'CLOSED'
                            AND closed_by IS NOT NULL AND closed_by <> creator_id GROUP BY closed_by""")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> Map.entry(rs.getLong("user_id"), rs.getInt("total")))
                    .forEach(entry -> counts.computeIfAbsent(entry.getKey(), key -> new int[3])[1] = entry.getValue());
            handle.createQuery("""
                            SELECT m.author_id AS user_id, COUNT(*) AS total FROM ticket_messages m JOIN tickets t ON t.id = m.ticket_id
                            WHERE t.guild_id = :guildId AND m.author_id <> t.creator_id GROUP BY m.author_id""")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> Map.entry(rs.getLong("user_id"), rs.getInt("total")))
                    .forEach(entry -> {
                        int[] values = counts.get(entry.getKey());
                        if (values != null) {
                            values[2] = entry.getValue();
                        }
                    });
            Map<Long, double[]> ratings = new LinkedHashMap<>();
            handle.createQuery("""
                            SELECT supporter_id, AVG(stars) AS average, COUNT(*) AS total FROM ticket_ratings
                            WHERE guild_id = :guildId AND supporter_id IS NOT NULL GROUP BY supporter_id""")
                    .bind("guildId", guildId)
                    .map((rs, ctx) -> Map.entry(rs.getLong("supporter_id"), new double[]{rs.getDouble("average"), rs.getInt("total")}))
                    .forEach(entry -> {
                        ratings.put(entry.getKey(), entry.getValue());
                        counts.computeIfAbsent(entry.getKey(), key -> new int[3]);
                    });
            List<SupporterStats> supporters = new ArrayList<>();
            counts.forEach((userId, values) -> {
                double[] rating = ratings.getOrDefault(userId, new double[]{0, 0});
                supporters.add(new SupporterStats(userId, values[0], values[1], values[2], rating[0], (int) rating[1]));
            });
            supporters.sort((a, b) -> Integer.compare(b.claimed() + b.closed(), a.claimed() + a.closed()));
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Map<LocalDate, int[]> days = new LinkedHashMap<>();
            for (int i = 29; i >= 0; i--) {
                days.put(today.minusDays(i), new int[2]);
            }
            Timestamp since = Timestamp.from(today.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC));
            handle.createQuery("""
                            SELECT DATE(created_at) AS day, COUNT(*) AS total FROM tickets WHERE guild_id = :guildId AND created_at >= :since
                            GROUP BY DATE(created_at)""")
                    .bind("guildId", guildId)
                    .bind("since", since)
                    .map((rs, ctx) -> Map.entry(rs.getDate("day").toLocalDate(), rs.getInt("total")))
                    .forEach(entry -> {
                        int[] values = days.get(entry.getKey());
                        if (values != null) {
                            values[0] = entry.getValue();
                        }
                    });
            handle.createQuery("""
                            SELECT DATE(closed_at) AS day, COUNT(*) AS total FROM tickets WHERE guild_id = :guildId AND closed_at >= :since
                            GROUP BY DATE(closed_at)""")
                    .bind("guildId", guildId)
                    .bind("since", since)
                    .map((rs, ctx) -> Map.entry(rs.getDate("day").toLocalDate(), rs.getInt("total")))
                    .forEach(entry -> {
                        int[] values = days.get(entry.getKey());
                        if (values != null) {
                            values[1] = entry.getValue();
                        }
                    });
            List<DayStats> series = days.entrySet().stream()
                    .map(entry -> new DayStats(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                    .toList();
            return new Stats((int) totals[0], (int) totals[1], (int) totals[2], (int) totals[3], (Double) totals[4], (Double) totals[5],
                    (Double) ratingTotals[0], (int) ratingTotals[1], categories, supporters, series);
        });
    }

    List<Expired> expired(long guildId, Instant closedBefore) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, guild_id, uuid FROM tickets WHERE guild_id = :guildId AND status = 'CLOSED' AND closed_at < :before""")
                .bind("guildId", guildId)
                .bind("before", Timestamp.from(closedBefore))
                .map((rs, ctx) -> new Expired(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("uuid")))
                .list());
    }

    List<Expired> byStatus(long guildId, Status status) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id, guild_id, uuid FROM tickets WHERE guild_id = :guildId AND status = :status")
                .bind("guildId", guildId)
                .bind("status", status.name())
                .map((rs, ctx) -> new Expired(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("uuid")))
                .list());
    }

    List<Long> guildsWithRetention() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT guild_id FROM ticket_settings WHERE retention_days IS NOT NULL")
                .mapTo(Long.class)
                .list());
    }

    long count(long guildId, Status status) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM tickets WHERE guild_id = :guildId AND status = :status")
                .bind("guildId", guildId)
                .bind("status", status.name())
                .mapTo(Long.class)
                .one());
    }

    void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM tickets WHERE id = :id").bind("id", id).execute());
    }

    private void update(long id, String assignment, Object value) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE tickets SET " + assignment + " WHERE id = :id")
                .bind("id", id)
                .bind("value", value)
                .execute());
    }

    private List<Answer> readAnswers(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return json.readValue(value, ANSWERS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored ticket answers are not valid JSON", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value is not serializable", e);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
