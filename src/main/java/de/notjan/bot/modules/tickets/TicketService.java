package de.notjan.bot.modules.tickets;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.message.BotChannelRepository;
import de.notjan.bot.modules.tickets.Ticket.Answer;
import de.notjan.bot.modules.tickets.Ticket.ApplicationState;
import de.notjan.bot.modules.tickets.Ticket.EventType;
import de.notjan.bot.modules.tickets.Ticket.Status;
import de.notjan.bot.modules.tickets.TicketConfig.BlacklistEntry;
import de.notjan.bot.modules.tickets.TicketConfig.Category;
import de.notjan.bot.modules.tickets.TicketConfig.Settings;
import de.notjan.bot.modules.tickets.TicketConfig.Target;
import de.notjan.bot.modules.tickets.TicketConfig.TargetType;
import de.notjan.bot.modules.tickets.TicketRepository.ChannelRef;
import de.notjan.bot.modules.tickets.TicketRepository.Expired;
import de.notjan.bot.util.Brand;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class TicketService {

    private static final Logger LOG = LoggerFactory.getLogger(TicketService.class);

    static final String MODULE = "tickets";
    static final int MAX_CLOSE_REQUEST_HOURS = 24 * 14;
    private static final int MAX_CHANNELS_PER_CATEGORY = 50;
    private static final int MAX_NOTE_MEMBERS = 100;
    private static final long SWEEP_SECONDS = 60;
    private static final long RETENTION_HOURS = 6;

    private final TicketConfigRepository config;
    private final TicketRepository tickets;
    private final TranscriptRepository transcripts;
    private final AssetStore assets;
    private final TicketPermissions permissions;
    private final BotChannelRepository botChannels;
    private final AuditLogService audit;
    private final String dashboardUrl;
    private final TranscriptRecorder recorder;
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("tickets-", 0).daemon().factory());
    private volatile JDA jda;

    public TicketService(TicketConfigRepository config, TicketRepository tickets, TranscriptRepository transcripts, AssetStore assets,
                         TicketPermissions permissions, BotChannelRepository botChannels, AuditLogService audit, String dashboardUrl,
                         TranscriptRecorder recorder) {
        this.config = config;
        this.tickets = tickets;
        this.transcripts = transcripts;
        this.assets = assets;
        this.permissions = permissions;
        this.botChannels = botChannels;
        this.audit = audit;
        this.dashboardUrl = dashboardUrl;
        this.recorder = recorder;
        recorder.onFirstResponse(tickets::setFirstResponse);
    }

    void start(JDA jda) {
        if (this.jda != null) {
            return;
        }
        this.jda = jda;
        for (ChannelRef ref : tickets.openChannels()) {
            Guild guild = jda.getGuildById(ref.guildId());
            Ticket ticket = tickets.find(ref.ticketId()).orElse(null);
            if (guild == null || ticket == null) {
                continue;
            }
            if (guild.getTextChannelById(ref.channelId()) == null) {
                closeWithoutChannel(guild, ticket, "Der Ticket-Kanal war beim Start des Bots nicht mehr vorhanden.");
                continue;
            }
            track(ticket);
        }
        executor.scheduleWithFixedDelay(this::closeDueRequests, 15, SWEEP_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::applyRetention, 1, RETENTION_HOURS * 60, TimeUnit.MINUTES);
    }

    Optional<Ticket> find(long id) {
        return tickets.find(id);
    }

    Settings settings(long guildId) {
        return config.settings(guildId);
    }

    Ticket require(Guild guild, long id) {
        return tickets.find(id).filter(ticket -> ticket.guildId() == guild.getIdLong())
                .orElseThrow(() -> new UserFacingException("Dieses Ticket gibt es nicht mehr."));
    }

    Ticket requireOpen(Guild guild, long id) {
        Ticket ticket = require(guild, id);
        if (!ticket.isOpen()) {
            throw new UserFacingException("Dieses Ticket ist bereits geschlossen.");
        }
        return ticket;
    }

    Ticket requireByChannel(GuildChannel channel) {
        return tickets.findOpenByChannel(channel.getIdLong())
                .filter(ticket -> ticket.guildId() == channel.getGuild().getIdLong())
                .orElseThrow(() -> new UserFacingException("Dieser Befehl funktioniert nur in einem offenen Ticket."));
    }

    Category requireCategory(Guild guild, long categoryId) {
        return config.category(categoryId).filter(category -> category.guildId() == guild.getIdLong())
                .orElseThrow(() -> new UserFacingException("Diese Ticket-Kategorie gibt es nicht mehr."));
    }

    List<Category> categories(long guildId) {
        return config.categories(guildId);
    }

    Category categoryOf(Ticket ticket) {
        return ticket.categoryId() == null ? null : config.category(ticket.categoryId()).orElse(null);
    }

    boolean isStaff(Member member, Ticket ticket) {
        return permissions.isStaff(member, settings(member.getGuild().getIdLong()), categoryOf(ticket));
    }

    boolean isStaff(Member member, Category category) {
        return permissions.isStaff(member, settings(member.getGuild().getIdLong()), category);
    }

    void requireStaff(Member member, Ticket ticket) {
        if (!isStaff(member, ticket)) {
            throw new UserFacingException("Das darf nur das Team.");
        }
    }

    void checkCanOpen(Member member, Category category) {
        Guild guild = member.getGuild();
        List<Long> roleIds = member.getRoles().stream().map(ISnowflake::getIdLong).toList();
        Optional<BlacklistEntry> blocked = config.blacklisted(guild.getIdLong(), member.getIdLong(), roleIds);
        if (blocked.isPresent()) {
            BlacklistEntry entry = blocked.get();
            throw new UserFacingException("Du bist vom Ticketsystem ausgeschlossen."
                    + (entry.reason() == null ? "" : "\n**Grund:** " + entry.reason())
                    + (entry.expiresAt() == null ? "" : "\nDie Sperre endet <t:" + entry.expiresAt().getEpochSecond() + ":R>."));
        }
        if (!category.requiredRoleIds().isEmpty() && roleIds.stream().noneMatch(category.requiredRoleIds()::contains)) {
            throw new UserFacingException("Für „" + category.name() + "“ brauchst du eine dieser Rollen: "
                    + category.requiredRoleIds().stream().map(id -> "<@&" + id + ">").collect(Collectors.joining(", ")));
        }
        if (category.maxOpen() > 0 && tickets.countOpenByCreator(guild.getIdLong(), member.getIdLong(), category.id()) >= category.maxOpen()) {
            throw new UserFacingException(category.maxOpen() == 1
                    ? "Du hast in „" + category.name() + "“ schon ein offenes Ticket."
                    : "Du hast in „" + category.name() + "“ schon " + category.maxOpen() + " offene Tickets.");
        }
        if (category.cooldownMinutes() > 0) {
            Optional<Instant> last = tickets.lastCreated(guild.getIdLong(), member.getIdLong(), category.id());
            if (last.isPresent()) {
                Instant next = last.get().plus(Duration.ofMinutes(category.cooldownMinutes()));
                if (next.isAfter(Instant.now())) {
                    throw new UserFacingException("Du kannst in „" + category.name() + "“ erst wieder <t:" + next.getEpochSecond()
                            + ":R> ein Ticket öffnen.");
                }
            }
        }
    }

    Ticket open(Guild guild, Member creator, Member actor, Category category, List<Answer> answers, List<Message.Attachment> files) {
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES)) {
            throw new UserFacingException("Dem Bot fehlen die Rechte „Kanäle verwalten“ und „Rollen verwalten“, um Tickets zu erstellen.");
        }
        Settings settings = settings(guild.getIdLong());
        int number = config.nextNumber(guild.getIdLong());
        String uuid = UUID.randomUUID().toString();
        Ticket draft = new Ticket(0, uuid, guild.getIdLong(), number, category.id(), category.name(), null, "", null, null, null, answers,
                creator.getIdLong(), creator.getUser().getName(), actor.getIdLong(), null, Status.OPEN,
                category.application() ? ApplicationState.PENDING : null, null, null, null, null, null, Instant.now(), null, null, null);
        String channelName = TicketRenderer.channelName(category.channelPattern(), draft, creator.getUser().getName());
        net.dv8tion.jda.api.entities.channel.concrete.Category parent = parent(guild, category);
        TextChannel channel;
        try {
            var action = guild.createTextChannel(channelName, parent)
                    .setTopic("Ticket #" + number + " · " + category.name() + " · " + creator.getUser().getName());
            channel = TicketPermissions.apply(action, permissions.overwrites(guild, draft, settings, category, List.of()))
                    .reason("Ticket #" + number + " von " + creator.getUser().getName())
                    .complete();
        } catch (ErrorResponseException | InsufficientPermissionException e) {
            LOG.warn("Could not create ticket channel in guild {}", guild.getId(), e);
            throw new UserFacingException("Der Ticket-Kanal konnte nicht erstellt werden. Prüfe die Rechte des Bots in der Kategorie.");
        }
        long id = tickets.insert(new TicketRepository.NewTicket(uuid, guild.getIdLong(), number, category.id(), category.name(),
                channel.getIdLong(), channel.getName(), answers, creator.getIdLong(), creator.getUser().getName(), actor.getIdLong(),
                category.application() ? ApplicationState.PENDING : null));
        botChannels.insert(guild.getIdLong(), channel.getIdLong(), MODULE, TicketRenderer.truncate("Ticket #" + number + " · " + category.name(), 100));
        Ticket ticket = tickets.find(id).orElseThrow();
        recorder.track(channel.getIdLong(), id, creator.getIdLong(), false, false);
        tickets.event(id, EventType.OPENED, actor.getIdLong(), creator.getIdLong(), actor.getIdLong() == creator.getIdLong()
                ? null : "Für " + creator.getUser().getName() + " geöffnet");

        Message welcome = channel.sendMessage(TicketRenderer.welcome(guild, ticket, category, settings, TicketRenderer.pingRoles(category, settings)))
                .complete();
        tickets.setControlMessage(id, welcome.getIdLong());
        if (!answers.isEmpty()) {
            sendAnswers(ticket, channel, answers, files);
        }
        if (actor.getIdLong() != creator.getIdLong()) {
            channel.sendMessageEmbeds(TicketRenderer.notice(actor.getAsMention() + " hat dieses Ticket für " + creator.getAsMention() + " geöffnet."))
                    .queue(null, error -> LOG.debug("Could not post opener note", error));
        }
        return tickets.find(id).orElseThrow();
    }

    void claim(Ticket ticket, Member actor) {
        requireStaff(actor, ticket);
        if (ticket.claimedBy() != null) {
            throw new UserFacingException(ticket.claimedBy() == actor.getIdLong()
                    ? "Du bearbeitest dieses Ticket bereits."
                    : "<@" + ticket.claimedBy() + "> bearbeitet dieses Ticket bereits.");
        }
        tickets.setClaimedBy(ticket.id(), actor.getIdLong());
        tickets.event(ticket.id(), EventType.CLAIMED, actor.getIdLong(), null, null);
        Ticket updated = refresh(actor.getGuild(), ticket);
        post(actor.getGuild(), updated, "🙋 " + actor.getAsMention() + " kümmert sich ab jetzt um dieses Ticket.");
    }

    void unclaim(Ticket ticket, Member actor) {
        requireStaff(actor, ticket);
        if (ticket.claimedBy() == null) {
            throw new UserFacingException("Dieses Ticket bearbeitet gerade niemand.");
        }
        if (ticket.claimedBy() != actor.getIdLong() && !permissions.isManager(actor)) {
            throw new UserFacingException("Nur <@" + ticket.claimedBy() + "> oder Admins können das Ticket freigeben.");
        }
        tickets.setClaimedBy(ticket.id(), null);
        tickets.event(ticket.id(), EventType.UNCLAIMED, actor.getIdLong(), ticket.claimedBy(), null);
        Ticket updated = refresh(actor.getGuild(), ticket);
        post(actor.getGuild(), updated, "↩️ " + actor.getAsMention() + " hat das Ticket wieder freigegeben.");
    }

    void transfer(Ticket ticket, Member actor, Member target) {
        requireStaff(actor, ticket);
        Settings settings = settings(actor.getGuild().getIdLong());
        if (target.getUser().isBot()) {
            throw new UserFacingException("Tickets können nicht an Bots übergeben werden.");
        }
        if (target.getIdLong() == ticket.creatorId()) {
            throw new UserFacingException("Das Ticket kann nicht an die Person übergeben werden, die es geöffnet hat.");
        }
        if (ticket.claimedBy() != null && ticket.claimedBy() == target.getIdLong()) {
            throw new UserFacingException(target.getAsMention() + " bearbeitet das Ticket bereits.");
        }
        boolean targetIsStaff = permissions.isStaff(target, settings, categoryOf(ticket));
        if (!targetIsStaff && !settings.transferToAnyone()) {
            throw new UserFacingException(target.getAsMention() + " gehört nicht zum Support-Team. Übergaben an andere Personen sind in den "
                    + "Ticket-Einstellungen ausgeschaltet.");
        }
        if (!targetIsStaff) {
            tickets.addMember(ticket.id(), new Target(TargetType.USER, target.getIdLong()), actor.getIdLong());
        }
        tickets.setClaimedBy(ticket.id(), target.getIdLong());
        tickets.event(ticket.id(), EventType.TRANSFERRED, actor.getIdLong(), target.getIdLong(), null);
        Ticket updated = refresh(actor.getGuild(), ticket);
        post(actor.getGuild(), updated, "🔁 " + actor.getAsMention() + " hat das Ticket an " + target.getAsMention() + " übergeben.");
    }

    void add(Ticket ticket, Member actor, Target target) {
        requireStaff(actor, ticket);
        Guild guild = actor.getGuild();
        if (target.type() == TargetType.USER && target.id() == ticket.creatorId()) {
            throw new UserFacingException("Diese Person hat das Ticket geöffnet und ist schon drin.");
        }
        if (target.type() == TargetType.ROLE && target.id() == guild.getIdLong()) {
            throw new UserFacingException("@everyone kann nicht hinzugefügt werden.");
        }
        if (tickets.members(ticket.id()).stream().anyMatch(member -> member.type() == target.type() && member.targetId() == target.id())) {
            throw new UserFacingException(mention(target) + " ist schon im Ticket.");
        }
        tickets.addMember(ticket.id(), target, actor.getIdLong());
        tickets.event(ticket.id(), EventType.MEMBER_ADDED, actor.getIdLong(), target.id(), target.type().name());
        Ticket updated = refresh(guild, ticket);
        post(guild, updated, "➕ " + actor.getAsMention() + " hat " + mention(target) + " zum Ticket hinzugefügt.");
    }

    void remove(Ticket ticket, Member actor, Target target) {
        requireStaff(actor, ticket);
        Guild guild = actor.getGuild();
        if (target.type() == TargetType.USER && target.id() == ticket.creatorId()) {
            throw new UserFacingException("Die Person, die das Ticket geöffnet hat, kann nicht entfernt werden. Schließe das Ticket stattdessen.");
        }
        boolean removed = tickets.removeMember(ticket.id(), target);
        if (target.type() == TargetType.USER && ticket.claimedBy() != null && ticket.claimedBy() == target.id()) {
            tickets.setClaimedBy(ticket.id(), null);
            removed = true;
        }
        if (!removed) {
            throw new UserFacingException(mention(target) + " wurde nicht extra hinzugefügt. Team-Rollen änderst du in den Ticket-Einstellungen.");
        }
        tickets.event(ticket.id(), EventType.MEMBER_REMOVED, actor.getIdLong(), target.id(), target.type().name());
        Ticket updated = refresh(guild, ticket);
        post(guild, updated, "➖ " + actor.getAsMention() + " hat " + mention(target) + " aus dem Ticket entfernt.");
    }

    void rename(Ticket ticket, Member actor, String name) {
        requireStaff(actor, ticket);
        TextChannel channel = channel(actor.getGuild(), ticket);
        String cleaned = TicketRenderer.channelName(name, ticket, ticket.creatorName());
        channel.getManager().setName(cleaned).complete();
        tickets.setChannelName(ticket.id(), cleaned);
        tickets.event(ticket.id(), EventType.RENAMED, actor.getIdLong(), null, cleaned);
    }

    void move(Ticket ticket, Member actor, Category category) {
        requireStaff(actor, ticket);
        if (ticket.categoryId() != null && ticket.categoryId() == category.id()) {
            throw new UserFacingException("Das Ticket ist bereits in „" + category.name() + "“.");
        }
        Guild guild = actor.getGuild();
        TextChannel channel = channel(guild, ticket);
        tickets.setCategory(ticket.id(), category.id(), category.name());
        tickets.event(ticket.id(), EventType.MOVED, actor.getIdLong(), null, ticket.categoryName() + " → " + category.name());
        var parent = parent(guild, category);
        if (parent != null && (channel.getParentCategory() == null || channel.getParentCategory().getIdLong() != parent.getIdLong())) {
            channel.getManager().setParent(parent).complete();
        }
        Ticket updated = refresh(guild, ticket);
        post(guild, updated, "📂 " + actor.getAsMention() + " hat das Ticket nach **" + category.name() + "** verschoben.");
    }

    void requestClose(Ticket ticket, Member actor, Integer hours, String reason) {
        requireStaff(actor, ticket);
        Settings settings = settings(actor.getGuild().getIdLong());
        int duration = hours == null ? settings.closeRequestHours() : hours;
        if (duration < 1 || duration > MAX_CLOSE_REQUEST_HOURS) {
            throw new UserFacingException("Die Zeit muss zwischen 1 und " + MAX_CLOSE_REQUEST_HOURS + " Stunden liegen.");
        }
        TextChannel channel = channel(actor.getGuild(), ticket);
        disableCloseRequest(channel, ticket, null);
        Instant until = Instant.now().plus(Duration.ofHours(duration));
        Message message = channel.sendMessage(TicketRenderer.closeRequest(ticket, actor.getIdLong(), reason, until)).complete();
        tickets.setCloseRequest(ticket.id(), actor.getIdLong(), reason, until, message.getIdLong());
        tickets.event(ticket.id(), EventType.CLOSE_REQUESTED, actor.getIdLong(), null,
                duration + " Std." + (reason == null ? "" : " · " + reason));
    }

    void cancelCloseRequest(Ticket ticket, Member actor) {
        if (actor.getIdLong() != ticket.creatorId() && !isStaff(actor, ticket)) {
            throw new UserFacingException("Nur die Person, die das Ticket geöffnet hat, oder das Team können das entscheiden.");
        }
        if (ticket.closeRequestUntil() == null) {
            throw new UserFacingException("Es gibt keinen offenen Schließantrag.");
        }
        tickets.clearCloseRequest(ticket.id());
        tickets.event(ticket.id(), EventType.CLOSE_REQUEST_CANCELLED, actor.getIdLong(), null, null);
        TextChannel channel = channel(actor.getGuild(), ticket);
        disableCloseRequest(channel, ticket, "✋ " + actor.getAsMention() + " möchte das Ticket offen lassen.");
    }

    void requireCanClose(Ticket ticket, Member member) {
        if (isStaff(member, ticket)) {
            return;
        }
        if (member.getIdLong() == ticket.creatorId() && settings(member.getGuild().getIdLong()).creatorCanClose()) {
            return;
        }
        throw new UserFacingException(member.getIdLong() == ticket.creatorId()
                ? "Tickets kann auf diesem Server nur das Team schließen."
                : "Nur das Team oder die Person, die das Ticket geöffnet hat, können es schließen.");
    }

    void close(Guild guild, Ticket ticket, long actorId, String reason) {
        Instant now = Instant.now();
        String cleaned = reason == null || reason.isBlank() ? null : TicketRenderer.truncate(reason.strip(), 1000);
        if (!tickets.close(ticket.id(), actorId, cleaned, now)) {
            throw new UserFacingException("Dieses Ticket ist bereits geschlossen.");
        }
        tickets.event(ticket.id(), EventType.CLOSED, actorId, null, cleaned);
        Settings settings = settings(guild.getIdLong());
        TextChannel channel = ticket.channelId() == null ? null : guild.getTextChannelById(ticket.channelId());
        if (channel != null) {
            disableCloseRequest(channel, ticket, null);
            channel.sendMessageEmbeds(TicketRenderer.notice(Brand.DANGER, "🔒 Das Ticket wurde von <@" + actorId + "> geschlossen"
                            + (cleaned == null ? "." : ".\n**Grund:** " + cleaned)
                            + "\n\nDer Kanal wird " + (settings.closeDelaySeconds() <= 0 ? "gleich" : "in " + settings.closeDelaySeconds()
                            + " Sekunden") + " gelöscht. Der Verlauf bleibt im Transkript erhalten."))
                    .queue(null, error -> LOG.debug("Could not post close notice", error));
        }
        executor.execute(() -> finish(guild.getIdLong(), ticket.id(), now, true));
    }

    ThreadChannel notes(Ticket ticket, Member actor) {
        requireStaff(actor, ticket);
        Guild guild = actor.getGuild();
        Settings settings = settings(guild.getIdLong());
        if (!settings.notesEnabled()) {
            throw new UserFacingException("Team-Notizen sind in den Ticket-Einstellungen ausgeschaltet.");
        }
        TextChannel channel = channel(guild, ticket);
        ThreadChannel existing = ticket.notesThreadId() == null ? null : guild.getThreadChannelById(ticket.notesThreadId());
        if (existing != null) {
            existing.addThreadMember(actor).queue(null, error -> LOG.debug("Could not add member to notes thread", error));
            return existing;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.CREATE_PRIVATE_THREADS, Permission.MESSAGE_SEND_IN_THREADS)) {
            throw new UserFacingException("Dem Bot fehlen die Rechte „Private Threads erstellen“ und „Nachrichten in Threads senden“. "
                    + "Vergib die Rechte im Dashboard erneut.");
        }
        ThreadChannel thread = channel.createThreadChannel("🔒 Team-Notizen", true)
                .setInvitable(false)
                .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
                .complete();
        tickets.setNotesThread(ticket.id(), thread.getIdLong());
        recorder.track(thread.getIdLong(), ticket.id(), ticket.creatorId(), true, true);
        thread.sendMessageEmbeds(TicketRenderer.notice("📝 **Team-Notizen zu Ticket #" + ticket.number() + "**\n\n"
                        + "Dieser private Thread ist nur für das Team sichtbar. <@" + ticket.creatorId() + "> kann ihn nicht sehen. "
                        + "Notizen erscheinen im Transkript als interne Nachrichten."))
                .queue(null, error -> LOG.debug("Could not post notes intro", error));
        thread.addThreadMember(actor).queue(null, error -> LOG.debug("Could not add member to notes thread", error));
        addStaffToThread(guild, thread, settings, categoryOf(ticket), actor.getIdLong());
        return thread;
    }

    void decide(Ticket ticket, Member actor, boolean accept, String reason) {
        requireStaff(actor, ticket);
        if (ticket.applicationState() != ApplicationState.PENDING) {
            throw new UserFacingException("Über diese Bewerbung wurde bereits entschieden.");
        }
        Guild guild = actor.getGuild();
        Category category = categoryOf(ticket);
        ApplicationState state = accept ? ApplicationState.ACCEPTED : ApplicationState.DENIED;
        tickets.setApplicationState(ticket.id(), state);
        tickets.event(ticket.id(), accept ? EventType.ACCEPTED : EventType.DENIED, actor.getIdLong(), ticket.creatorId(), reason);
        Ticket updated = tickets.find(ticket.id()).orElseThrow();
        List<String> problems = new ArrayList<>();
        if (accept && category != null && category.acceptRoleId() != null) {
            Role role = guild.getRoleById(category.acceptRoleId());
            Member applicant = guild.getMemberById(ticket.creatorId());
            if (role == null || applicant == null) {
                problems.add("Die Rolle konnte nicht vergeben werden, weil die Rolle oder die Person nicht mehr auf dem Server ist.");
            } else {
                try {
                    guild.addRoleToMember(applicant, role).reason("Bewerbung in Ticket #" + ticket.number() + " angenommen").complete();
                } catch (ErrorResponseException | InsufficientPermissionException | net.dv8tion.jda.api.exceptions.HierarchyException e) {
                    problems.add("Die Rolle " + role.getAsMention() + " konnte nicht vergeben werden. Steht die Bot-Rolle über ihr?");
                }
            }
        }
        TextChannel channel = guild.getTextChannelById(ticket.channelId());
        if (channel != null && ticket.answersMessageId() != null) {
            channel.editMessageEmbedsById(ticket.answersMessageId(), TicketRenderer.answers(updated, updated.answers(), state))
                    .setComponents(List.of())
                    .queue(null, error -> LOG.debug("Could not update application message", error));
        }
        String template = category == null ? null : accept ? category.acceptMessage() : category.denyMessage();
        String dm = (template == null || template.isBlank()
                ? (accept ? "🎉 Deine Bewerbung auf **{server}** wurde angenommen!" : "Deine Bewerbung auf **{server}** wurde leider abgelehnt.")
                : template).replace("{server}", guild.getName()).replace("{user}", "<@" + ticket.creatorId() + ">");
        if (reason != null && !reason.isBlank()) {
            dm += "\n\n**Begründung:** " + reason.strip();
        }
        sendDm(ticket.creatorId(), TicketRenderer.notice(accept ? TicketRenderer.SUCCESS : Brand.DANGER, dm), List.of());
        post(guild, updated, (accept ? "✅ " : "❌ ") + actor.getAsMention() + " hat die Bewerbung " + (accept ? "angenommen" : "abgelehnt") + "."
                + (reason == null || reason.isBlank() ? "" : "\n**Begründung:** " + reason.strip())
                + (problems.isEmpty() ? "" : "\n\n⚠️ " + String.join("\n⚠️ ", problems)));
        if (category != null && category.closeOnDecision()) {
            close(guild, updated, actor.getIdLong(), accept ? "Bewerbung angenommen" : "Bewerbung abgelehnt");
        }
    }

    boolean rate(long ticketId, long userId, int stars, String comment) {
        Ticket ticket = tickets.find(ticketId).orElseThrow(() -> new UserFacingException("Dieses Ticket gibt es nicht mehr."));
        if (ticket.creatorId() != userId) {
            throw new UserFacingException("Nur die Person, die das Ticket geöffnet hat, kann es bewerten.");
        }
        if (ticket.isOpen()) {
            throw new UserFacingException("Das Ticket ist noch offen.");
        }
        if (!settings(ticket.guildId()).ratingsEnabled()) {
            throw new UserFacingException("Bewertungen sind auf diesem Server ausgeschaltet.");
        }
        if (stars < 1 || stars > 5) {
            throw new UserFacingException("Ungültige Bewertung.");
        }
        Long supporter = supporter(ticket);
        String cleaned = comment == null || comment.isBlank() ? null : TicketRenderer.truncate(comment.strip(), 1000);
        return tickets.saveRating(ticket.id(), ticket.guildId(), userId, supporter, stars, cleaned);
    }

    Long supporter(Ticket ticket) {
        if (ticket.claimedBy() != null) {
            return ticket.claimedBy();
        }
        if (ticket.closedBy() != null && ticket.closedBy() != ticket.creatorId() && jda != null
                && ticket.closedBy() != jda.getSelfUser().getIdLong()) {
            return ticket.closedBy();
        }
        return jda == null ? null : tickets.mostActiveStaff(ticket.id(), ticket.creatorId(), jda.getSelfUser().getIdLong()).orElse(null);
    }

    List<BlacklistEntry> blacklist(long guildId) {
        return config.blacklist(guildId);
    }

    void blacklistAdd(Guild guild, long actorId, Source source, Target target, String reason, Integer days) {
        if (target.type() == TargetType.ROLE && target.id() == guild.getIdLong()) {
            throw new UserFacingException("@everyone kann nicht gesperrt werden.");
        }
        if (target.type() == TargetType.USER && jda != null && target.id() == jda.getSelfUser().getIdLong()) {
            throw new UserFacingException("Der Bot kann sich nicht selbst sperren.");
        }
        if (days != null && (days < 1 || days > 3650)) {
            throw new UserFacingException("Die Dauer muss zwischen 1 und 3650 Tagen liegen.");
        }
        Instant expires = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        String cleaned = reason == null || reason.isBlank() ? null : TicketRenderer.truncate(reason.strip(), 500);
        config.blacklistAdd(guild.getIdLong(), target, cleaned, expires, actorId);
        audit.record(guild, actorId, source, "tickets.blacklist.add", mention(target) + " vom Ticketsystem gesperrt"
                        + (days == null ? "" : " für " + days + (days == 1 ? " Tag" : " Tage")),
                Map.of("targetId", String.valueOf(target.id()), "type", target.type().name()));
    }

    void blacklistRemove(Guild guild, long actorId, Source source, Target target) {
        if (!config.blacklistRemove(guild.getIdLong(), target)) {
            throw new UserFacingException(mention(target) + " ist nicht gesperrt.");
        }
        audit.record(guild, actorId, source, "tickets.blacklist.remove", "Sperre für " + mention(target) + " im Ticketsystem aufgehoben",
                Map.of("targetId", String.valueOf(target.id()), "type", target.type().name()));
    }

    void onChannelDeleted(Guild guild, long channelId) {
        tickets.findOpenByChannel(channelId).ifPresent(ticket -> {
            if (ticket.channelId() != null && ticket.channelId() == channelId) {
                closeWithoutChannel(guild, ticket, "Der Ticket-Kanal wurde in Discord gelöscht.");
            }
        });
    }

    String transcriptUrl(Ticket ticket) {
        return dashboardUrl + "/transcripts/" + ticket.uuid();
    }

    void deleteTicketData(Expired ticket) {
        tickets.delete(ticket.id());
        assets.deleteTicket(ticket.guildId(), ticket.uuid());
    }

    void deleteClosed(long guildId) {
        tickets.byStatus(guildId, Status.CLOSED).forEach(this::deleteTicketData);
    }

    void deleteOpen(long guildId) {
        tickets.byStatus(guildId, Status.OPEN).forEach(ticket -> {
            recorder.untrack(ticket.id());
            deleteTicketData(ticket);
        });
    }

    long count(long guildId, Status status) {
        return tickets.count(guildId, status);
    }

    void refreshPermissions(Guild guild, Ticket ticket) {
        TextChannel channel = guild.getTextChannelById(ticket.channelId());
        if (channel == null) {
            return;
        }
        Ticket current = tickets.find(ticket.id()).orElse(ticket);
        TicketPermissions.sync(channel, permissions.overwrites(guild, current, settings(guild.getIdLong()), categoryOf(current),
                tickets.members(ticket.id())));
    }

    private Ticket refresh(Guild guild, Ticket ticket) {
        Ticket updated = tickets.find(ticket.id()).orElseThrow();
        try {
            refreshPermissions(guild, updated);
        } catch (ErrorResponseException | InsufficientPermissionException e) {
            LOG.warn("Could not update permissions of ticket {}", ticket.id(), e);
            throw new UserFacingException("Die Kanalrechte konnten nicht angepasst werden. Prüfe, ob der Bot „Rollen verwalten“ hat.");
        }
        TextChannel channel = guild.getTextChannelById(updated.channelId());
        if (channel != null && updated.controlMessageId() != null) {
            channel.editMessageComponentsById(updated.controlMessageId(), TicketRenderer.controls(updated, settings(guild.getIdLong())))
                    .queue(null, error -> LOG.debug("Could not update ticket controls", error));
        }
        return updated;
    }

    private void post(Guild guild, Ticket ticket, String text) {
        TextChannel channel = ticket.channelId() == null ? null : guild.getTextChannelById(ticket.channelId());
        if (channel != null) {
            channel.sendMessageEmbeds(TicketRenderer.notice(text)).queue(null, error -> LOG.debug("Could not post ticket notice", error));
        }
    }

    private TextChannel channel(Guild guild, Ticket ticket) {
        TextChannel channel = ticket.channelId() == null ? null : guild.getTextChannelById(ticket.channelId());
        if (channel == null) {
            throw new UserFacingException("Der Ticket-Kanal existiert nicht mehr.");
        }
        return channel;
    }

    private void sendAnswers(Ticket ticket, TextChannel channel, List<Answer> answers, List<Message.Attachment> files) {
        MessageCreateBuilder message = new MessageCreateBuilder()
                .setEmbeds(TicketRenderer.answers(ticket, answers, ticket.applicationState()));
        if (ticket.applicationState() == ApplicationState.PENDING) {
            message.setComponents(TicketRenderer.applicationButtons(ticket));
        }
        List<FileUpload> uploads = new ArrayList<>();
        for (Message.Attachment file : files.stream().limit(10).toList()) {
            if (file.getSize() > 8L * 1024 * 1024 * 3) {
                continue;
            }
            try {
                uploads.add(FileUpload.fromData(assets.fetch(file.getUrl()), file.getFileName()));
            } catch (Exception e) {
                LOG.warn("Could not download form attachment {} for ticket {}", file.getFileName(), ticket.id(), e);
            }
        }
        message.setFiles(uploads);
        try {
            Message sent = channel.sendMessage(message.build()).complete();
            tickets.setAnswersMessage(ticket.id(), sent.getIdLong());
        } catch (ErrorResponseException e) {
            LOG.warn("Could not post answers of ticket {}", ticket.id(), e);
        }
    }

    private void disableCloseRequest(TextChannel channel, Ticket ticket, String note) {
        if (ticket.closeRequestMessageId() == null) {
            return;
        }
        channel.editMessageComponentsById(ticket.closeRequestMessageId(), List.<ActionRow>of())
                .queue(null, error -> LOG.debug("Could not disable close request", error));
        if (note != null) {
            channel.sendMessageEmbeds(TicketRenderer.notice(note)).queue(null, error -> LOG.debug("Could not post close request note", error));
        }
    }

    private void addStaffToThread(Guild guild, ThreadChannel thread, Settings settings, Category category, long actorId) {
        boolean global = TicketPermissions.usesGlobalTeam(category);
        Set<Long> roleIds = new HashSet<>(global ? settings.staffRoleIds() : Set.of());
        if (category != null) {
            roleIds.addAll(category.supportRoleIds());
        }
        Set<Long> added = new HashSet<>(Set.of(actorId));
        for (long userId : global ? settings.staffUserIds() : Set.<Long>of()) {
            if (added.add(userId)) {
                thread.addThreadMemberById(userId).queue(null, error -> LOG.debug("Could not add staff user to notes thread", error));
            }
        }
        for (long roleId : roleIds) {
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                continue;
            }
            guild.findMembersWithRoles(role).onSuccess(members -> members.stream()
                    .filter(member -> !member.getUser().isBot())
                    .filter(member -> added.size() < MAX_NOTE_MEMBERS && added.add(member.getIdLong()))
                    .forEach(member -> thread.addThreadMember(member).queue(null,
                            error -> LOG.debug("Could not add staff member to notes thread", error))));
        }
    }

    private net.dv8tion.jda.api.entities.channel.concrete.Category parent(Guild guild, Category category) {
        if (category.parentId() == null) {
            return null;
        }
        var parent = guild.getCategoryById(category.parentId());
        if (parent == null || parent.getChannels().size() >= MAX_CHANNELS_PER_CATEGORY) {
            return null;
        }
        return parent;
    }

    private void track(Ticket ticket) {
        recorder.track(ticket.channelId(), ticket.id(), ticket.creatorId(), false, ticket.firstResponseAt() != null);
        if (ticket.notesThreadId() != null) {
            recorder.track(ticket.notesThreadId(), ticket.id(), ticket.creatorId(), true, true);
        }
    }

    private void closeWithoutChannel(Guild guild, Ticket ticket, String reason) {
        Instant now = Instant.now();
        if (tickets.close(ticket.id(), guild.getSelfMember().getIdLong(), reason, now)) {
            tickets.event(ticket.id(), EventType.CLOSED, guild.getSelfMember().getIdLong(), null, reason);
            executor.execute(() -> finish(guild.getIdLong(), ticket.id(), now, false));
        }
    }

    private void finish(long guildId, long ticketId, Instant closedAt, boolean deleteChannel) {
        try {
            Guild guild = jda.getGuildById(guildId);
            Ticket ticket = tickets.find(ticketId).orElse(null);
            if (guild == null || ticket == null) {
                recorder.untrack(ticketId);
                return;
            }
            Settings settings = settings(guildId);
            TextChannel channel = ticket.channelId() == null ? null : guild.getTextChannelById(ticket.channelId());
            if (channel != null) {
                recorder.sync(ticket, channel, false);
            }
            ThreadChannel notes = ticket.notesThreadId() == null ? null : guild.getThreadChannelById(ticket.notesThreadId());
            if (notes != null) {
                recorder.sync(ticket, notes, true);
            }
            recorder.flush();
            recorder.untrack(ticketId);
            assets.awaitPending(ticketId, Duration.ofSeconds(60));

            String url = transcriptUrl(ticket);
            sendLog(guild, ticket, settings, url);
            if (settings.dmOnClose()) {
                boolean rating = settings.ratingsEnabled();
                String link = settings.creatorTranscript() ? url : null;
                sendSummary(guild, ticket, link, rating);
            }
            if (deleteChannel && channel != null) {
                long remaining = settings.closeDelaySeconds() * 1000L - Duration.between(closedAt, Instant.now()).toMillis();
                if (remaining > 0) {
                    Thread.sleep(remaining);
                }
                try {
                    channel.delete().reason("Ticket #" + ticket.number() + " geschlossen").complete();
                } catch (ErrorResponseException | InsufficientPermissionException e) {
                    LOG.warn("Could not delete channel of ticket {}", ticketId, e);
                    channel.sendMessageEmbeds(TicketRenderer.notice(Brand.DANGER, "⚠️ Der Kanal konnte nicht gelöscht werden. "
                            + "Dem Bot fehlt das Recht „Kanäle verwalten“.")).queue(null, error -> { });
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.error("Finishing ticket {} failed", ticketId, e);
        }
    }

    private void sendLog(Guild guild, Ticket ticket, Settings settings, String url) {
        if (settings.logChannelId() == null) {
            return;
        }
        TextChannel log = guild.getTextChannelById(settings.logChannelId());
        if (log == null || !guild.getSelfMember().hasPermission(log, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS)) {
            LOG.warn("Ticket log channel {} in guild {} is missing or not writable", settings.logChannelId(), guild.getId());
            return;
        }
        int messages = transcripts.countVisible(ticket.id());
        List<Long> participants = transcripts.participants(ticket.id()).stream()
                .filter(id -> id != guild.getSelfMember().getIdLong())
                .limit(20)
                .toList();
        try {
            log.sendMessageEmbeds(TicketRenderer.log(ticket, messages, participants, url, false))
                    .setComponents(TicketRenderer.transcriptButton(url))
                    .complete();
        } catch (ErrorResponseException | IllegalArgumentException e) {
            LOG.debug("Transcript button rejected, sending log with inline link: {}", e.getMessage());
            log.sendMessageEmbeds(TicketRenderer.log(ticket, messages, participants, url, true))
                    .queue(null, error -> LOG.warn("Could not send ticket log", error));
        }
    }

    private void sendSummary(Guild guild, Ticket ticket, String link, boolean rating) {
        try {
            var channel = jda.retrieveUserById(ticket.creatorId()).complete().openPrivateChannel().complete();
            try {
                channel.sendMessageEmbeds(TicketRenderer.summary(guild, ticket, link, rating, false))
                        .setComponents(TicketRenderer.summaryButtons(ticket, link, rating))
                        .complete();
            } catch (ErrorResponseException | IllegalArgumentException e) {
                if (e instanceof ErrorResponseException error && error.getErrorCode() == 50007) {
                    throw error;
                }
                channel.sendMessageEmbeds(TicketRenderer.summary(guild, ticket, link, rating, true))
                        .setComponents(TicketRenderer.summaryButtons(ticket, null, rating))
                        .complete();
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not DM ticket summary to {}", ticket.creatorId(), e);
        }
    }

    private void sendDm(long userId, net.dv8tion.jda.api.entities.MessageEmbed embed, List<ActionRow> rows) {
        if (jda == null) {
            return;
        }
        jda.retrieveUserById(userId)
                .flatMap(User::openPrivateChannel)
                .flatMap(channel -> channel.sendMessageEmbeds(embed).setComponents(rows))
                .queue(null, error -> LOG.debug("Could not DM user {}", userId, error));
    }

    private void closeDueRequests() {
        try {
            for (long id : tickets.dueCloseRequests(Instant.now())) {
                tickets.find(id).ifPresent(ticket -> {
                    Guild guild = jda.getGuildById(ticket.guildId());
                    if (guild == null) {
                        return;
                    }
                    try {
                        close(guild, ticket, guild.getSelfMember().getIdLong(), "Automatisch geschlossen: keine Antwort auf den Schließantrag"
                                + (ticket.closeRequestReason() == null ? "" : " (" + ticket.closeRequestReason() + ")"));
                    } catch (UserFacingException e) {
                        tickets.clearCloseRequest(id);
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("Closing due ticket requests failed", e);
        }
    }

    private void applyRetention() {
        try {
            for (long guildId : tickets.guildsWithRetention()) {
                Integer days = settings(guildId).retentionDays();
                if (days == null || days < 1) {
                    continue;
                }
                for (Expired expired : tickets.expired(guildId, Instant.now().minus(Duration.ofDays(days)))) {
                    deleteTicketData(expired);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Applying ticket retention failed", e);
        }
    }

    private static String mention(Target target) {
        return target.type() == TargetType.ROLE ? "<@&" + target.id() + ">" : "<@" + target.id() + ">";
    }
}
