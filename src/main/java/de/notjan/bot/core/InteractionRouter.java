package de.notjan.bot.core;

import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.util.Replies;
import de.notjan.bot.util.UserFacingException;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.BiConsumer;

final class InteractionRouter extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(InteractionRouter.class);

    private final Map<String, SlashCommand> commands;
    private final Map<String, ComponentHandler> handlers;

    InteractionRouter(Map<String, SlashCommand> commands, Map<String, ComponentHandler> handlers) {
        this.commands = Map.copyOf(commands);
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        SlashCommand command = commands.get(event.getName());
        if (command == null) {
            LOG.warn("Received unknown command /{}", event.getName());
            return;
        }
        run(event, "/" + event.getName(), () -> command.execute(event));
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        SlashCommand command = commands.get(event.getName());
        if (command == null) {
            return;
        }
        try {
            command.autocomplete(event);
        } catch (Exception e) {
            LOG.warn("Autocomplete for /{} failed", event.getName(), e);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        dispatch(event, event.getComponentId(), (handler, id) -> handler.onButton(event, id));
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        dispatch(event, event.getComponentId(), (handler, id) -> handler.onEntitySelect(event, id));
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        dispatch(event, event.getComponentId(), (handler, id) -> handler.onStringSelect(event, id));
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        dispatch(event, event.getModalId(), (handler, id) -> handler.onModal(event, id));
    }

    private void dispatch(IReplyCallback event, String rawId, BiConsumer<ComponentHandler, ComponentId> action) {
        ComponentId id;
        try {
            id = ComponentId.parse(rawId);
        } catch (IllegalArgumentException e) {
            LOG.debug("Ignoring foreign component id {}", rawId);
            return;
        }
        ComponentHandler handler = handlers.get(id.namespace());
        if (handler == null) {
            LOG.warn("No component handler for namespace '{}'", id.namespace());
            return;
        }
        run(event, rawId, () -> action.accept(handler, id));
    }

    private void run(IReplyCallback event, String label, Runnable action) {
        try {
            action.run();
        } catch (UserFacingException e) {
            Replies.error(event, String.join("\n", e.errors()));
        } catch (Exception e) {
            LOG.error("Interaction {} failed in guild {}", label, event.getGuild() == null ? "-" : event.getGuild().getId(), e);
            Replies.error(event, "Da ist etwas schiefgelaufen. Bitte versuche es erneut.");
        }
    }
}
