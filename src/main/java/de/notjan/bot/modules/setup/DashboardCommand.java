package de.notjan.bot.modules.setup;

import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

final class DashboardCommand implements SlashCommand {

    private final DashboardLinks links;
    private final GuildSettingsService settings;

    DashboardCommand(DashboardLinks links, GuildSettingsService settings) {
        this.links = links;
        this.settings = settings;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("dashboard", "Öffnet das Dashboard für diesen Server")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        boolean setupCompleted = settings.get(guild.getIdLong()).setupCompleted();

        String description = setupCompleted
                ? "Alle Einstellungen für **" + guild.getName() + "** findest du im Dashboard."
                : "**" + guild.getName() + "** ist noch nicht eingerichtet. Die Einrichtung dauert nur eine Minute.";
        var embed = new EmbedBuilder()
                .setColor(Brand.PRIMARY)
                .setTitle(setupCompleted ? "Dashboard" : "Einrichtung starten")
                .setDescription(description + links.inlineLink(guild))
                .build();

        event.replyEmbeds(embed)
                .setComponents(links.buttons(guild, setupCompleted ? "Dashboard öffnen" : "Jetzt einrichten"))
                .setEphemeral(true)
                .queue();
    }
}
