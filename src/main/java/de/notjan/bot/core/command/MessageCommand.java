package de.notjan.bot.core.command;

import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

public interface MessageCommand {

    CommandData data();

    void execute(MessageContextInteractionEvent event);

    default String name() {
        return data().getName();
    }
}
