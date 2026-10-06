package de.notjan.bot.guild;

import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildLifecycleListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(GuildLifecycleListener.class);

    private final GuildSettingsService settings;

    public GuildLifecycleListener(GuildSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        settings.get(event.getGuild().getIdLong());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        LOG.info("Joined guild {} ({})", event.getGuild().getName(), event.getGuild().getId());
        settings.get(event.getGuild().getIdLong());
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        LOG.info("Left guild {} ({})", event.getGuild().getName(), event.getGuild().getId());
    }
}
