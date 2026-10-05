package de.notjan.bot.core;

import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.List;
import java.util.Set;

/**
 * A self-contained bot feature (tickets, reaction roles, ...). Modules declare everything they
 * need from the platform; the {@link ModuleRegistry} wires it into JDA and the REST API.
 */
public interface BotModule {

    /** Stable identifier, also used as default namespace for component IDs. */
    String id();

    default Set<GatewayIntent> requiredIntents() {
        return Set.of();
    }

    default List<SlashCommand> commands() {
        return List.of();
    }

    default List<ComponentHandler> componentHandlers() {
        return List.of();
    }

    /** Plain JDA listeners for gateway events that are not interactions. */
    default List<Object> eventListeners() {
        return List.of();
    }

    /** REST endpoints for the dashboard. Called once JDA is ready. */
    default List<ApiController> apiControllers(JDA jda) {
        return List.of();
    }
}
