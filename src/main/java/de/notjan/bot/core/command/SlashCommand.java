package de.notjan.bot.core.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public interface SlashCommand {

    SlashCommandData data();

    void execute(SlashCommandInteractionEvent event);

    default String name() {
        return data().getName();
    }
}
