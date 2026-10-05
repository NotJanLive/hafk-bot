package de.notjan.bot.modules.setup;

import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.guild.GuildSettingsService;

import java.util.List;

/**
 * Onboarding: greets new guilds and links admins to the dashboard, where the setup happens.
 */
public final class SetupModule implements BotModule {

    private final DashboardLinks links;
    private final GuildSettingsService settings;

    public SetupModule(GuildSettingsService settings, String dashboardUrl) {
        this.settings = settings;
        this.links = new DashboardLinks(dashboardUrl);
    }

    @Override
    public String id() {
        return "setup";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(new DashboardCommand(links, settings));
    }

    @Override
    public List<Object> eventListeners() {
        return List.of(new WelcomeListener(links, settings));
    }
}
