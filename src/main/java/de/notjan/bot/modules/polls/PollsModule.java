package de.notjan.bot.modules.polls;

import de.notjan.bot.api.ApiContext;
import de.notjan.bot.api.ApiController;
import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.MessageCommand;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.reset.ResettableData;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;

public final class PollsModule implements BotModule {

    private final PollService service;

    public PollsModule(PollService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return PollService.MODULE;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(new PollCommand(service));
    }

    @Override
    public List<MessageCommand> messageCommands() {
        return List.of(new PollManageCommand(service));
    }

    @Override
    public List<ComponentHandler> componentHandlers() {
        return List.of(new PollComponentHandler(service));
    }

    @Override
    public List<Object> eventListeners() {
        return List.of(new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                service.start(event.getJDA());
            }
        });
    }

    @Override
    public List<ApiController> apiControllers(ApiContext context) {
        return List.of(new PollController(context.guard(), service));
    }

    @Override
    public List<ResettableData> resettableData() {
        return service.resettableData();
    }
}
