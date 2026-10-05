package de.notjan.bot.core.component;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

/**
 * Handles all components whose {@link ComponentId#namespace()} matches {@link #namespace()}.
 * Override only the interaction types the handler actually uses.
 */
public interface ComponentHandler {

    String namespace();

    default void onButton(ButtonInteractionEvent event, ComponentId id) {
        throw new UnsupportedOperationException("No button handler for " + id);
    }

    default void onEntitySelect(EntitySelectInteractionEvent event, ComponentId id) {
        throw new UnsupportedOperationException("No entity select handler for " + id);
    }

    default void onStringSelect(StringSelectInteractionEvent event, ComponentId id) {
        throw new UnsupportedOperationException("No string select handler for " + id);
    }

    default void onModal(ModalInteractionEvent event, ComponentId id) {
        throw new UnsupportedOperationException("No modal handler for " + id);
    }
}
