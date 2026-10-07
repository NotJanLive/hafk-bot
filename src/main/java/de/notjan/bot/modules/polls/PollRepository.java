package de.notjan.bot.modules.polls;

import de.notjan.bot.modules.polls.Poll.Option;
import de.notjan.bot.modules.polls.Poll.OptionDraft;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.util.UserFacingException;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class PollRepository {

    record PollRow(long id, long guildId, long channelId, Long messageRef, String question, String description, boolean anonymous,
                   ResultVisibility visibility, boolean hostResults, int maxChoices, boolean allowChange, Long pingRoleId,
                   String voterSalt, Instant endsAt, Instant closedAt, Long closedBy, boolean cancelled, long createdBy, Instant createdAt, List<Option> options,
                   Set<Long> allowedRoleIds) {
    }

    record NewPoll(long guildId, long channelId, String question, String description, boolean anonymous, ResultVisibility visibility,
                   boolean hostResults, int maxChoices, boolean allowChange, Long pingRoleId, String voterSalt, Instant endsAt, long createdBy,
                   List<OptionDraft> options, Set<Long> allowedRoleIds) {
    }

    record Tally(Map<Long, Integer> votes, int voters, Map<Long, List<Long>> votersByOption) {

        int votes(long optionId) {
            return votes.getOrDefault(optionId, 0);
        }

        int totalVotes() {
            return votes.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<Long> voters(long optionId) {
            return votersByOption.getOrDefault(optionId, List.of());
        }
    }

    private final Jdbi jdbi;

    public PollRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    long insert(NewPoll poll) {
        return jdbi.inTransaction(handle -> {
            long id = handle.createUpdate("""
                            INSERT INTO polls (guild_id, channel_id, question, description, anonymous, result_visibility, host_results,
                                               max_choices, allow_change, ping_role_id, voter_salt, ends_at, created_by)
                            VALUES (:guildId, :channelId, :question, :description, :anonymous, :visibility, :hostResults,
                                    :maxChoices, :allowChange, :pingRoleId, :voterSalt, :endsAt, :createdBy)""")
                    .bind("guildId", poll.guildId())
                    .bind("channelId", poll.channelId())
                    .bind("question", poll.question())
                    .bind("description", poll.description())
                    .bind("anonymous", poll.anonymous())
                    .bind("visibility", poll.visibility().name())
                    .bind("hostResults", poll.hostResults())
                    .bind("maxChoices", poll.maxChoices())
                    .bind("allowChange", poll.allowChange())
                    .bind("pingRoleId", poll.pingRoleId())
                    .bind("voterSalt", poll.voterSalt())
                    .bind("endsAt", poll.endsAt() == null ? null : Timestamp.from(poll.endsAt()))
                    .bind("createdBy", poll.createdBy())
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();
            var options = handle.prepareBatch("""
                    INSERT INTO poll_options (poll_id, label, emoji, position) VALUES (:pollId, :label, :emoji, :position)""");
            for (int i = 0; i < poll.options().size(); i++) {
                OptionDraft option = poll.options().get(i);
                options.bind("pollId", id).bind("label", option.label()).bind("emoji", option.emoji()).bind("position", i).add();
            }
            options.execute();
            if (!poll.allowedRoleIds().isEmpty()) {
                var roles = handle.prepareBatch("INSERT INTO poll_allowed_roles (poll_id, role_id) VALUES (:pollId, :roleId)");
                poll.allowedRoleIds().forEach(roleId -> roles.bind("pollId", id).bind("roleId", roleId).add());
                roles.execute();
            }
            return id;
        });
    }

    void setMessage(long id, long messageRef) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE polls SET message_ref = :messageRef WHERE id = :id")
                .bind("id", id)
                .bind("messageRef", messageRef)
                .execute());
    }

    Optional<PollRow> find(long id) {
        return jdbi.withHandle(handle -> load(handle, id));
    }

    Optional<PollRow> findByDiscordMessage(long messageId) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT p.id FROM polls p JOIN bot_messages m ON m.id = p.message_ref
                        WHERE m.message_id = :messageId""")
                .bind("messageId", messageId)
                .mapTo(Long.class)
                .findOne()
                .flatMap(id -> load(handle, id)));
    }

    List<PollRow> list(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id FROM polls WHERE guild_id = :guildId ORDER BY id DESC")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .list()
                .stream()
                .flatMap(id -> load(handle, id).stream())
                .toList());
    }

    List<Long> dueIds(Instant now) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT id FROM polls WHERE closed_at IS NULL AND ends_at <= :now")
                .bind("now", Timestamp.from(now))
                .mapTo(Long.class)
                .list());
    }

    boolean close(long id, Long closedBy, Instant closedAt, boolean cancelled) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        UPDATE polls SET closed_at = :closedAt, closed_by = :closedBy, cancelled = :cancelled
                        WHERE id = :id AND closed_at IS NULL""")
                .bind("id", id)
                .bind("closedAt", Timestamp.from(closedAt))
                .bind("closedBy", closedBy)
                .bind("cancelled", cancelled)
                .execute() > 0);
    }

    boolean extend(long id, Instant endsAt) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        UPDATE polls SET ends_at = :endsAt WHERE id = :id AND closed_at IS NULL AND ends_at IS NOT NULL""")
                .bind("id", id)
                .bind("endsAt", Timestamp.from(endsAt))
                .execute() > 0);
    }

    Set<Long> choices(long pollId, String voterKey) {
        return jdbi.withHandle(handle -> currentChoices(handle, pollId, voterKey));
    }

    Set<Long> vote(long pollId, String voterKey, Long userId, UnaryOperator<Set<Long>> rule) {
        return jdbi.inTransaction(handle -> {
            boolean open = handle.createQuery("SELECT closed_at IS NULL FROM polls WHERE id = :id FOR UPDATE")
                    .bind("id", pollId)
                    .mapTo(Boolean.class)
                    .findOne()
                    .orElse(false);
            if (!open) {
                throw new UserFacingException("Diese Umfrage ist bereits beendet.");
            }
            Set<Long> next = rule.apply(currentChoices(handle, pollId, voterKey));
            handle.createUpdate("DELETE FROM poll_votes WHERE poll_id = :pollId AND voter_key = :voterKey")
                    .bind("pollId", pollId)
                    .bind("voterKey", voterKey)
                    .execute();
            if (!next.isEmpty()) {
                var batch = handle.prepareBatch("""
                        INSERT INTO poll_votes (poll_id, option_id, voter_key, user_id)
                        VALUES (:pollId, :optionId, :voterKey, :userId)""");
                next.forEach(optionId -> batch.bind("pollId", pollId)
                        .bind("optionId", optionId)
                        .bind("voterKey", voterKey)
                        .bind("userId", userId)
                        .add());
                batch.execute();
            }
            return next;
        });
    }

    Tally tally(long pollId, boolean withVoters) {
        return jdbi.withHandle(handle -> {
            Map<Long, Integer> votes = new HashMap<>();
            handle.createQuery("SELECT option_id, COUNT(*) AS votes FROM poll_votes WHERE poll_id = :pollId GROUP BY option_id")
                    .bind("pollId", pollId)
                    .map((rs, ctx) -> Map.entry(rs.getLong("option_id"), rs.getInt("votes")))
                    .forEach(entry -> votes.put(entry.getKey(), entry.getValue()));
            int voters = handle.createQuery("SELECT COUNT(DISTINCT voter_key) FROM poll_votes WHERE poll_id = :pollId")
                    .bind("pollId", pollId)
                    .mapTo(Integer.class)
                    .one();
            Map<Long, List<Long>> byOption = new HashMap<>();
            if (withVoters) {
                handle.createQuery("""
                                SELECT option_id, user_id FROM poll_votes
                                WHERE poll_id = :pollId AND user_id IS NOT NULL ORDER BY created_at""")
                        .bind("pollId", pollId)
                        .map((rs, ctx) -> Map.entry(rs.getLong("option_id"), rs.getLong("user_id")))
                        .forEach(entry -> byOption.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
            }
            return new Tally(votes, voters, byOption);
        });
    }

    void delete(long id) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM polls WHERE id = :id").bind("id", id).execute());
    }

    long count(long guildId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM polls WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .one());
    }

    void deleteByGuild(long guildId) {
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM polls WHERE guild_id = :guildId").bind("guildId", guildId).execute());
    }

    Set<Long> creatorRoles(long guildId) {
        return jdbi.withHandle(handle -> new HashSet<>(handle.createQuery("SELECT role_id FROM poll_creator_roles WHERE guild_id = :guildId")
                .bind("guildId", guildId)
                .mapTo(Long.class)
                .list()));
    }

    void setCreatorRoles(long guildId, Set<Long> roleIds) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM poll_creator_roles WHERE guild_id = :guildId").bind("guildId", guildId).execute();
            if (!roleIds.isEmpty()) {
                var batch = handle.prepareBatch("INSERT INTO poll_creator_roles (guild_id, role_id) VALUES (:guildId, :roleId)");
                roleIds.forEach(roleId -> batch.bind("guildId", guildId).bind("roleId", roleId).add());
                batch.execute();
            }
        });
    }

    private static Set<Long> currentChoices(Handle handle, long pollId, String voterKey) {
        return new LinkedHashSet<>(handle.createQuery("""
                        SELECT v.option_id FROM poll_votes v JOIN poll_options o ON o.id = v.option_id
                        WHERE v.poll_id = :pollId AND v.voter_key = :voterKey ORDER BY o.position""")
                .bind("pollId", pollId)
                .bind("voterKey", voterKey)
                .mapTo(Long.class)
                .list());
    }

    private static Optional<PollRow> load(Handle handle, long id) {
        List<Option> options = handle.createQuery("SELECT id, label, emoji FROM poll_options WHERE poll_id = :id ORDER BY position")
                .bind("id", id)
                .map((rs, ctx) -> new Option(rs.getLong("id"), rs.getString("label"), rs.getString("emoji")))
                .list();
        Set<Long> roles = new HashSet<>(handle.createQuery("SELECT role_id FROM poll_allowed_roles WHERE poll_id = :id")
                .bind("id", id)
                .mapTo(Long.class)
                .list());
        return handle.createQuery("SELECT * FROM polls WHERE id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new PollRow(
                        rs.getLong("id"),
                        rs.getLong("guild_id"),
                        rs.getLong("channel_id"),
                        rs.getObject("message_ref", Long.class),
                        rs.getString("question"),
                        rs.getString("description"),
                        rs.getBoolean("anonymous"),
                        ResultVisibility.valueOf(rs.getString("result_visibility")),
                        rs.getBoolean("host_results"),
                        rs.getInt("max_choices"),
                        rs.getBoolean("allow_change"),
                        rs.getObject("ping_role_id", Long.class),
                        rs.getString("voter_salt"),
                        instant(rs.getTimestamp("ends_at")),
                        instant(rs.getTimestamp("closed_at")),
                        rs.getObject("closed_by", Long.class),
                        rs.getBoolean("cancelled"),
                        rs.getLong("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        options,
                        roles))
                .findOne();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
