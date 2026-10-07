package de.notjan.bot.modules.polls;

import de.notjan.bot.core.command.MessageCommand;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

final class PollManageCommand implements MessageCommand {

    private final PollService service;

    PollManageCommand(PollService service) {
        this.service = service;
    }

    @Override
    public CommandData data() {
        return Commands.message("Manage poll")
                .setNameLocalization(DiscordLocale.GERMAN, "Umfrage verwalten")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE));
    }

    @Override
    public void execute(MessageContextInteractionEvent event) {
        PollComponentHandler.defer(event);
        Poll poll = service.requireByDiscordMessage(event.getGuild(), event.getTarget().getIdLong());
        PollComponentHandler.showManageView(event, service, event.getMember(), poll);
    }
}
