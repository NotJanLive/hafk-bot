package de.notjan.bot.core;

import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.reset.ResettableData;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ModuleRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistry.class);

    private final List<BotModule> modules;
    private final Map<String, SlashCommand> commands = new HashMap<>();
    private final Map<String, ComponentHandler> componentHandlers = new HashMap<>();

    public ModuleRegistry(List<BotModule> modules) {
        this.modules = List.copyOf(modules);
        for (BotModule module : modules) {
            module.commands().forEach(command -> putUnique(commands, command.name(), command, "command"));
            module.componentHandlers().forEach(handler -> putUnique(componentHandlers, handler.namespace(), handler, "component namespace"));
        }
    }

    public Set<GatewayIntent> intents(Set<GatewayIntent> coreIntents) {
        EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
        intents.addAll(coreIntents);
        modules.forEach(module -> intents.addAll(module.requiredIntents()));
        return intents;
    }

    public List<Object> listeners() {
        List<Object> listeners = new ArrayList<>();
        listeners.add(new InteractionRouter(commands, componentHandlers));
        modules.forEach(module -> listeners.addAll(module.eventListeners()));
        return listeners;
    }

    public List<ApiController> apiControllers(ApiContext context) {
        return modules.stream().flatMap(module -> module.apiControllers(context).stream()).toList();
    }

    public List<ResettableData> resettableData() {
        return modules.stream().flatMap(module -> module.resettableData().stream()).toList();
    }

    public void registerCommands(JDA jda, Optional<Long> devGuildId) {
        List<CommandData> data = commands.values().stream().<CommandData>map(SlashCommand::data).toList();
        if (devGuildId.isPresent()) {
            Guild guild = jda.getGuildById(devGuildId.get());
            if (guild == null) {
                throw new IllegalStateException("Dev guild " + devGuildId.get() + " not found, is the bot a member?");
            }
            guild.updateCommands().addCommands(data).queue();
            LOG.info("Registered {} command(s) in dev guild {}", data.size(), guild.getName());
        } else {
            jda.updateCommands().addCommands(data).queue();
            LOG.info("Registered {} global command(s)", data.size());
        }
    }

    public List<String> moduleIds() {
        return modules.stream().map(BotModule::id).toList();
    }

    private static <T> void putUnique(Map<String, T> target, String key, T value, String kind) {
        if (target.putIfAbsent(key, value) != null) {
            throw new IllegalStateException("Duplicate " + kind + " '" + key + "'");
        }
    }
}
