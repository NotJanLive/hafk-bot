package de.notjan.bot.core;

import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.reset.ResettableData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.List;
import java.util.Set;

public interface BotModule {

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

    default List<Object> eventListeners() {
        return List.of();
    }

    default List<ApiController> apiControllers(ApiContext context) {
        return List.of();
    }

    default List<ResettableData> resettableData() {
        return List.of();
    }
}
