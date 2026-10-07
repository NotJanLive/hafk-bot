package de.notjan.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.api.ApiServer;
import de.notjan.bot.api.GuildGuard;
import de.notjan.bot.api.controller.GuildController;
import de.notjan.bot.api.controller.SystemController;
import de.notjan.bot.audit.AuditLogRepository;
import de.notjan.bot.audit.AuditLogService;
import de.notjan.bot.config.BotConfig;
import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.ModuleRegistry;
import de.notjan.bot.database.Database;
import de.notjan.bot.guild.GuildLifecycleListener;
import de.notjan.bot.guild.GuildSettingsRepository;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.guild.SettingsUpdater;
import de.notjan.bot.message.BotChannelRepository;
import de.notjan.bot.message.BotMessageCleanupListener;
import de.notjan.bot.message.BotMessageRepository;
import de.notjan.bot.message.BotMessageService;
import de.notjan.bot.modules.embeds.EmbedService;
import de.notjan.bot.modules.embeds.EmbedTemplateRepository;
import de.notjan.bot.modules.embeds.EmbedsModule;
import de.notjan.bot.modules.polls.PollRepository;
import de.notjan.bot.modules.polls.PollService;
import de.notjan.bot.modules.polls.PollsModule;
import de.notjan.bot.modules.reactionroles.ReactionRoleRepository;
import de.notjan.bot.modules.reactionroles.ReactionRoleService;
import de.notjan.bot.modules.reactionroles.ReactionRolesModule;
import de.notjan.bot.modules.setup.SetupModule;
import de.notjan.bot.reset.ResetController;
import de.notjan.bot.reset.ResetService;
import de.notjan.bot.reset.ResettableData;
import de.notjan.bot.util.Json;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class HAFKBot {

    private static final Logger LOG = LoggerFactory.getLogger(HAFKBot.class);

    private static final Set<GatewayIntent> CORE_INTENTS = Set.of(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES);

    private HAFKBot() {
    }

    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.load();
        ObjectMapper json = Json.mapper();
        Database database = Database.connect(config.database());

        GuildSettingsService settings = new GuildSettingsService(new GuildSettingsRepository(database.jdbi()));
        AuditLogService audit = new AuditLogService(new AuditLogRepository(database.jdbi()), json);
        SettingsUpdater settingsUpdater = new SettingsUpdater(settings, audit);
        AccessService access = new AccessService(settings);

        BotMessageRepository messageRepository = new BotMessageRepository(database.jdbi(), json);
        BotChannelRepository channelRepository = new BotChannelRepository(database.jdbi());
        BotMessageService messages = new BotMessageService(messageRepository);

        List<BotModule> modules = List.of(
                new SetupModule(settings, config.dashboardUrl()),
                new EmbedsModule(new EmbedService(messages, new EmbedTemplateRepository(database.jdbi(), json), audit), access),
                new ReactionRolesModule(new ReactionRoleService(new ReactionRoleRepository(database.jdbi()), messages, audit)),
                new PollsModule(new PollService(new PollRepository(database.jdbi()), messages, audit, access, config.api().token()))
        );
        ModuleRegistry registry = new ModuleRegistry(modules);

        List<ResettableData> resettable = new ArrayList<>(List.of(
                ResettableData.required("core.settings", "Einstellungen & Einrichtung",
                        "Dashboard-Rollen und Einrichtungsstatus. Danach startet die Einrichtung neu.",
                        guildId -> 1, settings::delete),
                ResettableData.of("core.audit-log", "Änderungsprotokoll", "Alle bisherigen Einträge des Protokolls",
                        audit::count, audit::deleteAll)));
        resettable.addAll(registry.resettableData());
        ResetService reset = new ResetService(resettable, messages, channelRepository, audit);

        JDA jda = JDABuilder.createLight(config.discordToken(), registry.intents(CORE_INTENTS))
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setActivity(Activity.watching("Hans & Friends"))
                .addEventListeners(new GuildLifecycleListener(settings), new BotMessageCleanupListener(messageRepository, channelRepository))
                .addEventListeners(registry.listeners().toArray())
                .build()
                .awaitReady();
        registry.registerCommands(jda, config.devGuildId());

        GuildGuard guard = new GuildGuard(jda, access);
        List<ApiController> controllers = new ArrayList<>(List.of(
                new SystemController(jda, access, settings),
                new GuildController(guard, settings, settingsUpdater, audit),
                new ResetController(guard, reset)));
        controllers.addAll(registry.apiControllers(new ApiContext(jda, guard)));
        ApiServer api = ApiServer.start(config.api(), json, controllers);

        LOG.info("{} ready in {} guild(s) with modules {}", jda.getSelfUser().getName(), jda.getGuilds().size(), registry.moduleIds());

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            LOG.info("Shutting down");
            api.close();
            jda.shutdown();
            database.close();
        }));
    }
}
