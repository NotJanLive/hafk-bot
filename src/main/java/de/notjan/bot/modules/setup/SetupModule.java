package de.notjan.bot.modules.setup;

import de.notjan.bot.core.BotModule;
import de.notjan.bot.core.command.SlashCommand;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.guild.SettingsUpdater;

import java.util.List;

/**
 * In-Discord setup: an interactive /setup panel mirroring the dashboard's core settings page.
 */
public final class SetupModule implements BotModule {

    static final String ID = "setup";

    private final SetupPanel panel;
    private final GuildSettingsService settings;
    private final SettingsUpdater updater;

    public SetupModule(GuildSettingsService settings, SettingsUpdater updater, String dashboardUrl) {
        this.settings = settings;
        this.updater = updater;
        this.panel = new SetupPanel(dashboardUrl);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(new SetupCommand(panel, settings));
    }

    @Override
    public List<ComponentHandler> componentHandlers() {
        return List.of(new SetupComponentHandler(panel, updater));
    }
}
