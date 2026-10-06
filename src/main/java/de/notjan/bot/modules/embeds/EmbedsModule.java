package de.notjan.bot.modules.embeds;

import de.notjan.bot.access.AccessService;
import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.reset.ResettableData;

import java.util.List;

public final class EmbedsModule implements BotModule {

    private final EmbedService embeds;
    private final AccessService access;

    public EmbedsModule(EmbedService embeds, AccessService access) {
        this.embeds = embeds;
        this.access = access;
    }

    @Override
    public String id() {
        return EmbedService.MODULE;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(new EmbedCommand(embeds, access));
    }

    @Override
    public List<ComponentHandler> componentHandlers() {
        return List.of(new EmbedModalHandler(embeds, access));
    }

    @Override
    public List<ApiController> apiControllers(ApiContext context) {
        return List.of(new EmbedController(context.guard(), embeds));
    }

    @Override
    public List<ResettableData> resettableData() {
        return embeds.resettableData();
    }
}
