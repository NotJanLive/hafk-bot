package de.notjan.bot.modules.reactionroles;

import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.reset.ResettableData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.List;
import java.util.Set;

public final class ReactionRolesModule implements BotModule {

    private final ReactionRoleService service;

    public ReactionRolesModule(ReactionRoleService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return ReactionRoleService.MODULE;
    }

    @Override
    public Set<GatewayIntent> requiredIntents() {
        return Set.of(GatewayIntent.GUILD_MESSAGE_REACTIONS);
    }

    @Override
    public List<ComponentHandler> componentHandlers() {
        return List.of(new ReactionRoleComponentHandler(service));
    }

    @Override
    public List<Object> eventListeners() {
        return List.of(new ReactionRoleReactionListener(service));
    }

    @Override
    public List<ApiController> apiControllers(ApiContext context) {
        return List.of(new ReactionRoleController(context.guard(), service));
    }

    @Override
    public List<ResettableData> resettableData() {
        return service.resettableData();
    }
}
