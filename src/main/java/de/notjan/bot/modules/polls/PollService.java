package de.notjan.bot.modules.polls;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.message.BotMessageService.Sent;
import de.notjan.bot.modules.polls.Poll.Draft;
import de.notjan.bot.modules.polls.Poll.OptionDraft;
import de.notjan.bot.modules.polls.Poll.ResultVisibility;
import de.notjan.bot.modules.polls.PollRepository.NewPoll;
import de.notjan.bot.modules.polls.PollRepository.PollRow;
import de.notjan.bot.modules.polls.PollRepository.Tally;
import de.notjan.bot.reset.ResettableData;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PollService {

    private static final Logger LOG = LoggerFactory.getLogger(PollService.class);

    static final String MODULE = "polls";
    static final String NAMESPACE = "poll";
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 20;
    static final int MAX_QUESTION = 256;
    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_LABEL = 80;
    static final int MAX_ALLOWED_ROLES = 25;
    static final int MAX_DURATION_MINUTES = 60 * 24 * 30;
    private static final long REFRESH_DELAY_MILLIS = 2_000;
    private static final long SWEEP_INTERVAL_SECONDS = 30;
    private static final int VARIATION_SELECTOR = 0xFE0F;
    private static final Pattern INVALID_COMPONENT = Pattern.compile("components\\[(\\d+)]\\.components\\[(\\d+)]");

    record PersonalView(String text, boolean canWithdraw) {
    }

    private final PollRepository repository;
    private final BotMessageService messages;
    private final AuditLogService audit;
    private final AccessService access;
    private final byte[] voterSecret;
    private final SecureRandom random = new SecureRandom();
    private final Set<Long> pendingRefresh = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("polls").daemon().factory());
    private volatile JDA jda;

    public PollService(PollRepository repository, BotMessageService messages, AuditLogService audit, AccessService access,
                       String voterSecret) {
        this.repository = repository;
        this.messages = messages;
        this.audit = audit;
        this.access = access;
        this.voterSecret = voterSecret.getBytes(StandardCharsets.UTF_8);
    }

    void start(JDA jda) {
        if (this.jda != null) {
            return;
        }
        this.jda = jda;
        executor.scheduleWithFixedDelay(this::closeDue, 5, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public List<Poll> list(long guildId) {
        return repository.list(guildId).stream().map(this::toPoll).toList();
    }

    public Poll require(long guildId, long id) {
        return repository.find(id).filter(row -> row.guildId() == guildId).map(this::toPoll)
                .orElseThrow(() -> UserFacingException.notFound("Diese Umfrage existiert nicht (mehr)."));
    }

    public Poll requireForInteraction(Guild guild, long id) {
        return repository.find(id).filter(row -> row.guildId() == guild.getIdLong()).map(this::toPoll)
                .orElseThrow(() -> new UserFacingException("Diese Umfrage gibt es nicht mehr."));
    }

    public Tally tally(Poll poll, boolean withVoters) {
        return repository.tally(poll.id(), withVoters && !poll.anonymous());
    }

    public Poll create(Guild guild, long userId, Source source, Draft draft) {
        validate(guild, draft);
        Instant endsAt = draft.durationMinutes() == null || draft.durationMinutes() == 0
                ? null
                : Instant.now().plus(Duration.ofMinutes(draft.durationMinutes()));
        List<OptionDraft> options = draft.options().stream()
                .map(option -> new OptionDraft(option.label().strip(), normalizeEmoji(blankToNull(option.emoji()))))
                .toList();
        long id = repository.insert(new NewPoll(guild.getIdLong(), draft.channelId(), draft.question().strip(),
                blankToNull(draft.description()), draft.anonymous(), draft.visibility(), draft.hostResults(), draft.maxChoices(),
                draft.allowChange(), draft.pingRoleId(), newSalt(), endsAt, userId, options, draft.allowedRoleIds()));
        Poll poll = require(guild.getIdLong(), id);
        try {
            Sent sent = messages.send(guild, draft.channelId(), MODULE, label(poll), PollRenderer.message(poll, tally(poll, false)),
                    PollRenderer.components(poll), userId);
            repository.setMessage(id, sent.record().id());
        } catch (UserFacingException e) {
            repository.delete(id);
            throw invalidEmoji(e, poll).orElse(e);
        } catch (RuntimeException e) {
            repository.delete(id);
            throw e;
        }
        audit.record(guild, userId, source, "polls.create",
                (poll.anonymous() ? "Anonyme Umfrage" : "Umfrage") + " „" + label(poll) + "“ in <#" + draft.channelId() + "> gestartet",
                Map.of("pollId", String.valueOf(id)));
        return require(guild.getIdLong(), id);
    }

    public PersonalView vote(Member member, Poll poll, long optionId) {
        requireOpen(poll);
        requireAllowed(member, poll);
        if (poll.option(optionId).isEmpty()) {
            throw new UserFacingException("Diese Antwort gehört nicht mehr zur Umfrage.");
        }
        Set<Long> choices = repository.vote(poll.id(), voterKey(poll, member.getIdLong()),
                poll.anonymous() ? null : member.getIdLong(),
                current -> VoteRules.click(poll.maxChoices(), poll.allowChange(), current, optionId));
        requestRefresh(poll.id());
        if (choices.isEmpty()) {
            return new PersonalView("Deine Stimme wurde zurückgezogen.", false);
        }
        StringBuilder reply = new StringBuilder("✅ Deine Stimme ist gezählt:\n").append(PollRenderer.choices(poll, choices));
        if (poll.maxChoices() > 1 && choices.size() < poll.maxChoices()) {
            reply.append("\n-# Du kannst noch ").append(poll.maxChoices() - choices.size()).append(" weitere Antwort(en) wählen.");
        }
        if (poll.anonymous()) {
            reply.append("\n\n🔒 Diese Umfrage ist anonym. Niemand sieht, wofür du gestimmt hast.");
        }
        switch (poll.visibility()) {
            case AFTER_VOTE -> reply.append("\n\n**Zwischenstand**\n").append(PollRenderer.results(poll, tally(poll, true), true));
            case CLOSED -> reply.append("\n\nDas Ergebnis wird nach dem Ende der Umfrage veröffentlicht.");
            case LIVE -> reply.append("\n\nDen Zwischenstand siehst du direkt in der Umfrage.");
        }
        return new PersonalView(PollRenderer.truncate(reply.toString()), poll.allowChange());
    }

    public Poll requireByDiscordMessage(Guild guild, long messageId) {
        return repository.findByDiscordMessage(messageId).filter(row -> row.guildId() == guild.getIdLong()).map(this::toPoll)
                .orElseThrow(() -> new UserFacingException("Diese Nachricht ist keine Umfrage des Bots."));
    }

    public PersonalView personalView(Member member, Poll poll) {
        Set<Long> choices = repository.choices(poll.id(), voterKey(poll, member.getIdLong()));
        boolean voted = !choices.isEmpty();
        StringBuilder text = new StringBuilder("**").append(poll.question()).append("**\n\n**Deine Stimme**\n");
        text.append(voted ? PollRenderer.choices(poll, choices) : "Du hast noch nicht abgestimmt. Klicke auf eine Antwort.");
        if (voted && poll.anonymous()) {
            text.append("\n-# 🔒 Nur du siehst deine Auswahl.");
        }
        if (poll.visibility() == ResultVisibility.AFTER_VOTE && voted) {
            text.append("\n\n**Zwischenstand**\n").append(PollRenderer.results(poll, tally(poll, true), true));
        } else if (poll.visibility() == ResultVisibility.AFTER_VOTE) {
            text.append("\n\n-# Das Ergebnis siehst du, sobald du abgestimmt hast.");
        } else if (poll.visibility() == ResultVisibility.CLOSED) {
            text.append("\n\n-# Das Ergebnis wird nach dem Ende der Umfrage veröffentlicht.");
        }
        return new PersonalView(PollRenderer.truncate(text.toString()), poll.isOpen() && poll.allowChange() && voted);
    }

    public String manageView(Member member, Poll poll) {
        requireManager(member, poll);
        Tally tally = tally(poll, true);
        StringBuilder text = new StringBuilder("**").append(poll.question()).append("**\n");
        String timing = PollRenderer.timing(poll);
        text.append(timing == null ? "Läuft ohne Enddatum" : timing).append(" · ")
                .append(tally.voters()).append(tally.voters() == 1 ? " Teilnahme" : " Teilnahmen");
        if (poll.managersSeeResults()) {
            text.append("\n\n**").append(poll.isOpen() ? "Zwischenstand" : "Ergebnis").append("**\n")
                    .append(PollRenderer.results(poll, tally, true));
        } else {
            text.append("\n\n🙈 Zwischenstände sind ausgeblendet, das Ergebnis gibt es zum Ende.");
        }
        if (poll.isOpen()) {
            text.append("\n\n-# Beende die Umfrage mit Ergebnis, brich sie ohne Ergebnis ab")
                    .append(poll.endsAt() == null ? "." : " oder verlängere die Laufzeit.");
        }
        return PollRenderer.truncate(text.toString());
    }

    public void requireManager(Member member, Poll poll) {
        if (!canManage(member, poll)) {
            throw new UserFacingException("Nur wer die Umfrage erstellt hat und das Server-Team dürfen sie verwalten.");
        }
    }

    public String withdraw(Member member, Poll poll) {
        requireOpen(poll);
        repository.vote(poll.id(), voterKey(poll, member.getIdLong()), null,
                current -> VoteRules.withdraw(poll.allowChange(), current));
        requestRefresh(poll.id());
        return "Deine Stimme wurde zurückgezogen.";
    }

    public boolean canManage(Member member, Poll poll) {
        return member.getIdLong() == poll.createdBy() || access.check(member.getGuild(), member.getIdLong()).isPresent();
    }

    public boolean canCreate(Member member) {
        if (access.check(member.getGuild(), member.getIdLong()).isPresent()) {
            return true;
        }
        Set<Long> creatorRoles = repository.creatorRoles(member.getGuild().getIdLong());
        return member.getRoles().stream().map(ISnowflake::getIdLong).anyMatch(creatorRoles::contains);
    }

    public Poll close(Guild guild, long userId, Source source, long id, boolean cancel) {
        Poll poll = require(guild.getIdLong(), id);
        requireOpen(poll);
        finish(guild, poll, userId, userId, source, cancel);
        return require(guild.getIdLong(), id);
    }

    public Poll extend(Guild guild, long userId, Source source, long id, int minutes) {
        Poll poll = require(guild.getIdLong(), id);
        requireOpen(poll);
        if (poll.endsAt() == null) {
            throw new UserFacingException("Diese Umfrage hat kein Enddatum.");
        }
        Instant now = Instant.now();
        Instant base = poll.endsAt().isAfter(now) ? poll.endsAt() : now;
        Instant endsAt = base.plus(Duration.ofMinutes(minutes));
        Instant limit = now.plus(Duration.ofMinutes(MAX_DURATION_MINUTES));
        if (endsAt.isAfter(limit)) {
            endsAt = limit;
        }
        if (!repository.extend(id, endsAt)) {
            throw new UserFacingException("Die Laufzeit konnte nicht verlängert werden.");
        }
        Poll extended = require(guild.getIdLong(), id);
        render(guild, extended);
        audit.record(guild, userId, source, "polls.extend",
                "Umfrage „" + label(poll) + "“ verlängert bis <t:" + endsAt.getEpochSecond() + ":f>", Map.of("pollId", String.valueOf(id)));
        return extended;
    }

    public void delete(Guild guild, long userId, long id, boolean deleteMessage) {
        Poll poll = require(guild.getIdLong(), id);
        if (poll.message() != null) {
            if (!deleteMessage) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, poll.message().channelId());
                if (channel != null) {
                    channel.editMessageComponentsById(poll.message().messageId(), List.<ActionRow>of()).queue(null, error -> { });
                }
            }
            messages.delete(guild, poll.message(), deleteMessage);
        }
        repository.delete(id);
        audit.record(guild, userId, Source.DASHBOARD, "polls.delete", "Umfrage „" + label(poll) + "“ gelöscht",
                Map.of("pollId", String.valueOf(id)));
    }

    public Set<Long> creatorRoles(long guildId) {
        return repository.creatorRoles(guildId);
    }

    public Set<Long> updateCreatorRoles(Guild guild, long userId, Set<Long> roleIds) {
        if (roleIds.size() > MAX_ALLOWED_ROLES) {
            throw new UserFacingException("Es sind höchstens " + MAX_ALLOWED_ROLES + " Rollen möglich.");
        }
        List<String> unknown = roleIds.stream().filter(roleId -> guild.getRoleById(roleId) == null).map(String::valueOf).toList();
        if (!unknown.isEmpty()) {
            throw new UserFacingException("Unbekannte Rolle(n): " + String.join(", ", unknown));
        }
        repository.setCreatorRoles(guild.getIdLong(), roleIds);
        audit.record(guild, userId, Source.DASHBOARD, "polls.settings",
                roleIds.isEmpty()
                        ? "Umfragen in Discord dürfen nur noch Dashboard-Berechtigte erstellen"
                        : "Rollen für Umfragen geändert: " + roleIds.stream().map(id -> "<@&" + id + ">").collect(Collectors.joining(", ")),
                Map.of("roleIds", roleIds.stream().map(String::valueOf).toList()));
        return repository.creatorRoles(guild.getIdLong());
    }

    List<ResettableData> resettableData() {
        return List.of(
                ResettableData.of("polls.polls", "Umfragen",
                        "Alle Umfragen samt Stimmen. Behaltene Nachrichten reagieren danach nicht mehr auf Klicks.",
                        repository::count,
                        guildId -> {
                            repository.deleteByGuild(guildId);
                            messages.deleteRecords(guildId, MODULE);
                        }),
                ResettableData.of("polls.settings", "Umfrage-Einstellungen", "Rollen, die in Discord Umfragen erstellen dürfen",
                        guildId -> repository.creatorRoles(guildId).size(),
                        guildId -> repository.setCreatorRoles(guildId, Set.of())));
    }

    void validate(Guild guild, Draft draft) {
        List<String> errors = new ArrayList<>();
        if (draft.visibility() == null) {
            errors.add("Lege fest, wann das Ergebnis sichtbar ist.");
        }
        if (isBlank(draft.question())) {
            errors.add("Die Frage fehlt.");
        } else if (draft.question().strip().length() > MAX_QUESTION) {
            errors.add("Die Frage darf höchstens " + MAX_QUESTION + " Zeichen haben.");
        }
        if (draft.description() != null && draft.description().strip().length() > MAX_DESCRIPTION) {
            errors.add("Die Beschreibung darf höchstens " + MAX_DESCRIPTION + " Zeichen haben.");
        }
        List<OptionDraft> options = draft.options() == null ? List.of() : draft.options();
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            errors.add("Eine Umfrage braucht " + MIN_OPTIONS + " bis " + MAX_OPTIONS + " Antworten.");
        }
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            OptionDraft option = options.get(i);
            String prefix = "Antwort " + (i + 1) + ": ";
            if (isBlank(option.label())) {
                errors.add(prefix + "Der Text fehlt.");
                continue;
            }
            if (option.label().strip().length() > MAX_LABEL) {
                errors.add(prefix + "Höchstens " + MAX_LABEL + " Zeichen.");
            }
            if (!labels.add(option.label().strip().toLowerCase(Locale.ROOT))) {
                errors.add(prefix + "Diese Antwort gibt es schon.");
            }
            if (!isBlank(option.emoji())) {
                try {
                    Emoji.fromFormatted(option.emoji().strip());
                } catch (IllegalArgumentException e) {
                    errors.add(prefix + "Das Emoji ist ungültig.");
                }
            }
        }
        if (draft.maxChoices() < 1 || (!options.isEmpty() && draft.maxChoices() > options.size())) {
            errors.add("Die Anzahl wählbarer Antworten muss zwischen 1 und " + Math.max(1, options.size()) + " liegen.");
        }
        if (draft.durationMinutes() != null && (draft.durationMinutes() < 0 || draft.durationMinutes() > MAX_DURATION_MINUTES)) {
            errors.add("Eine Umfrage kann höchstens 30 Tage laufen.");
        }
        Set<Long> roles = draft.allowedRoleIds() == null ? Set.of() : draft.allowedRoleIds();
        if (roles.size() > MAX_ALLOWED_ROLES) {
            errors.add("Es sind höchstens " + MAX_ALLOWED_ROLES + " Rollen möglich.");
        }
        roles.stream().filter(roleId -> guild.getRoleById(roleId) == null)
                .forEach(roleId -> errors.add("Die Rolle " + roleId + " existiert nicht."));
        if (draft.pingRoleId() != null) {
            Role role = guild.getRoleById(draft.pingRoleId());
            if (role == null) {
                errors.add("Die Rolle zum Pingen existiert nicht.");
            } else if (role.isPublicRole()) {
                errors.add("@everyone kann nicht als Ping-Rolle gewählt werden.");
            } else if (!role.isMentionable() && !guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE)) {
                errors.add("Der Bot darf @" + role.getName() + " nicht pingen. Erlaube in den Rolleneinstellungen „Jeder darf diese Rolle "
                        + "erwähnen“ oder gib dem Bot das Recht „@everyone, @here und alle Rollen erwähnen“.");
            }
        }
        if (!errors.isEmpty()) {
            throw new UserFacingException(errors);
        }
    }

    static Optional<UserFacingException> invalidEmoji(UserFacingException error, Poll poll) {
        String message = String.join(" ", error.errors());
        if (!message.contains("COMPONENT_INVALID_EMOJI")) {
            return Optional.empty();
        }
        Matcher matcher = INVALID_COMPONENT.matcher(message);
        if (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) * PollRenderer.BUTTONS_PER_ROW + Integer.parseInt(matcher.group(2));
            if (index < poll.options().size()) {
                return Optional.of(new UserFacingException("Antwort " + (index + 1) + " („" + poll.options().get(index).label()
                        + "“): Discord kennt dieses Emoji nicht. Bitte wähle ein anderes."));
            }
        }
        return Optional.of(new UserFacingException("Discord kennt eines der gewählten Emojis nicht. Bitte wähle ein anderes."));
    }

    static String normalizeEmoji(String emoji) {
        if (emoji == null) {
            return null;
        }
        int[] codePoints = emoji.codePoints().toArray();
        if (codePoints.length == 2 && codePoints[1] == VARIATION_SELECTOR && Character.isEmojiPresentation(codePoints[0])) {
            return Character.toString(codePoints[0]);
        }
        return emoji;
    }

    static OptionDraft parseOption(String line) {
        String text = line.strip().replaceFirst("^(?:[-*•]|\\d{1,2}[.)])\\s+", "");
        int space = text.indexOf(' ');
        if (space > 0) {
            String first = text.substring(0, space);
            if (looksLikeEmoji(first)) {
                return new OptionDraft(text.substring(space + 1).strip(), first);
            }
        }
        return new OptionDraft(text, null);
    }

    private static boolean looksLikeEmoji(String token) {
        if (token.matches("<a?:\\w{2,32}:\\d{17,20}>")) {
            return true;
        }
        return token.codePoints().noneMatch(codePoint -> codePoint < 0x80 || Character.isLetterOrDigit(codePoint));
    }

    void requestRefresh(long pollId) {
        if (pendingRefresh.add(pollId)) {
            executor.schedule(() -> {
                pendingRefresh.remove(pollId);
                refresh(pollId);
            }, REFRESH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void refresh(long pollId) {
        try {
            repository.find(pollId).map(this::toPoll).ifPresent(poll -> {
                Guild guild = jda == null ? null : jda.getGuildById(poll.guildId());
                if (guild != null) {
                    render(guild, poll);
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not refresh poll {}", pollId, e);
        }
    }

    private void render(Guild guild, Poll poll) {
        if (poll.message() == null) {
            return;
        }
        try {
            messages.edit(guild, poll.message(), label(poll), PollRenderer.message(poll, tally(poll, true)), PollRenderer.components(poll));
        } catch (UserFacingException e) {
            LOG.debug("Could not update poll message {}: {}", poll.id(), e.getMessage());
        }
    }

    private void finish(Guild guild, Poll poll, long actorId, Long closedBy, Source source, boolean cancel) {
        if (!repository.close(poll.id(), closedBy, Instant.now(), cancel)) {
            return;
        }
        Poll closed = require(guild.getIdLong(), poll.id());
        render(guild, closed);
        if (!cancel) {
            announceEnd(guild, closed, actorId);
        }
        String summary = cancel
                ? "Umfrage „" + label(poll) + "“ abgebrochen"
                : "Umfrage „" + label(poll) + "“ " + (closedBy == null ? "automatisch beendet" : "beendet");
        audit.record(guild, actorId, source, cancel ? "polls.cancel" : "polls.close", summary, Map.of("pollId", String.valueOf(poll.id())));
    }

    private void announceEnd(Guild guild, Poll poll, long actorId) {
        try {
            String jumpUrl = poll.message() == null ? null : poll.message().jumpUrl();
            String label = "Ergebnis: " + label(poll);
            messages.send(guild, poll.channelId(), MODULE, label.length() > 100 ? label.substring(0, 99) + "…" : label,
                    PollRenderer.endNotice(poll, tally(poll, true), jumpUrl), List.of(), actorId);
        } catch (RuntimeException e) {
            LOG.warn("Could not announce end of poll {}", poll.id(), e);
        }
    }

    private void closeDue() {
        try {
            for (long id : repository.dueIds(Instant.now())) {
                repository.find(id).map(this::toPoll).ifPresent(poll -> {
                    Guild guild = jda.getGuildById(poll.guildId());
                    if (guild == null) {
                        repository.close(id, null, Instant.now(), false);
                        return;
                    }
                    finish(guild, poll, guild.getSelfMember().getIdLong(), null, Source.DISCORD, false);
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("Closing due polls failed", e);
        }
    }

    private static void requireOpen(Poll poll) {
        if (!poll.isOpen()) {
            throw new UserFacingException("Diese Umfrage ist bereits beendet.");
        }
    }

    private static void requireAllowed(Member member, Poll poll) {
        if (poll.allowedRoleIds().isEmpty()) {
            return;
        }
        boolean allowed = member.getRoles().stream().map(Role::getIdLong).anyMatch(poll.allowedRoleIds()::contains);
        if (!allowed) {
            throw new UserFacingException("Bei dieser Umfrage dürfen nur bestimmte Rollen abstimmen.");
        }
    }

    String voterKey(Poll poll, long userId) {
        if (!poll.anonymous()) {
            return String.valueOf(userId);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(voterSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((poll.voterSalt() + ":" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private String newSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    private Poll toPoll(PollRow row) {
        var message = Optional.ofNullable(row.messageRef()).flatMap(ref -> messages.find(row.guildId(), ref)).orElse(null);
        return new Poll(row.id(), row.guildId(), row.channelId(), message, row.question(), row.description(), row.anonymous(),
                row.visibility(), row.hostResults(), row.maxChoices(), row.allowChange(), row.pingRoleId(), row.voterSalt(),
                row.endsAt(), row.closedAt(), row.closedBy(), row.cancelled(), row.createdBy(), row.createdAt(), row.options(),
                row.allowedRoleIds());
    }

    static String label(Poll poll) {
        String question = poll.question().strip();
        return question.length() > 100 ? question.substring(0, 99) + "…" : question;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
