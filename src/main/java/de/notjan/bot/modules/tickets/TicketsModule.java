package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.message.BotChannelRepository;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.modules.tickets.Ticket.Status;
import de.notjan.bot.reset.ResettableData;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Set;

public final class TicketsModule implements BotModule {

    private final ObjectMapper json;
    private final AccessService access;
    private final TicketConfigRepository configRepository;
    private final TicketRepository repository;
    private final TranscriptRepository transcripts;
    private final AssetStore assets;
    private final TranscriptRecorder recorder;
    private final TicketConfigService config;
    private final TicketService service;
    private final TicketForms forms = new TicketForms();

    public TicketsModule(Jdbi jdbi, ObjectMapper json, BotMessageService messages, BotChannelRepository channels, AuditLogService audit,
                         AccessService access, String dashboardUrl, String storageDir) {
        this.json = json;
        this.access = access;
        this.configRepository = new TicketConfigRepository(jdbi, json);
        this.repository = new TicketRepository(jdbi, json);
        this.transcripts = new TranscriptRepository(jdbi);
        this.assets = new AssetStore(storageDir, transcripts);
        this.recorder = new TranscriptRecorder(transcripts, new TranscriptSnapshot(json, assets), json,
                id -> repository.find(id).orElse(null));
        this.config = new TicketConfigService(configRepository, messages, audit);
        this.service = new TicketService(configRepository, repository, transcripts, assets, new TicketPermissions(access), channels, audit,
                dashboardUrl, recorder);
    }

    @Override
    public String id() {
        return TicketService.MODULE;
    }

    @Override
    public Set<GatewayIntent> requiredIntents() {
        return Set.of(GatewayIntent.MESSAGE_CONTENT);
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(new TicketCommand(service));
    }

    @Override
    public List<ComponentHandler> componentHandlers() {
        return List.of(new TicketComponentHandler(service, forms));
    }

    @Override
    public List<Object> eventListeners() {
        return List.of(recorder, new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                service.start(event.getJDA());
            }

            @Override
            public void onChannelDelete(ChannelDeleteEvent event) {
                if (event.isFromGuild() && recorder.isTracked(event.getChannel().getIdLong())) {
                    service.onChannelDeleted(event.getGuild(), event.getChannel().getIdLong());
                }
            }
        });
    }

    @Override
    public List<ApiController> apiControllers(ApiContext context) {
        return List.of(
                new TicketController(context.guard(), service, config, repository),
                new TranscriptController(new TranscriptService(context.jda(), access, repository, transcripts, configRepository, assets, json)));
    }

    @Override
    public List<ResettableData> resettableData() {
        return List.of(
                ResettableData.of("tickets.config", "Ticket-Einrichtung",
                        "Kategorien, Formulare, Panels, Einstellungen und Zugriffsrechte. Gesendete Panels reagieren danach nicht mehr.",
                        config::countConfig, config::deleteConfig),
                ResettableData.of("tickets.open", "Offene Tickets",
                        "Datensätze und bisherige Verläufe offener Tickets. Die Kanäle selbst löschst du unten bei den Kanälen.",
                        guildId -> service.count(guildId, Status.OPEN), service::deleteOpen),
                ResettableData.of("tickets.transcripts", "Ticket-Transkripte",
                        "Alle geschlossenen Tickets mit Chatverläufen, gespeicherten Dateien und Bewertungen.",
                        guildId -> service.count(guildId, Status.CLOSED), service::deleteClosed),
                ResettableData.of("tickets.blacklist", "Ticket-Sperren", "Alle Personen und Rollen, die vom Ticketsystem gesperrt sind.",
                        config::countBlacklist, config::deleteBlacklist));
    }
}
