package de.notjan.bot.modules.setup;

import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.guild.GuildSettingsService;

import java.util.List;

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
