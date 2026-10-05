package de.notjan.bot.modules.setup;

import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.guild.GuildSettingsService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

final class SetupCommand implements SlashCommand {

    private final SetupPanel panel;
    private final GuildSettingsService settings;

    SetupCommand(SetupPanel panel, GuildSettingsService settings) {
        this.panel = panel;
        this.settings = settings;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("setup", "Richte den Bot für diesen Server ein")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        event.reply(panel.render(guild, settings.get(guild.getIdLong()))).setEphemeral(true).queue();
    }
}
