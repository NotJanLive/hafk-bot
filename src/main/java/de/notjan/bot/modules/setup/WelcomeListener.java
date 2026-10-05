package de.notjan.bot.modules.setup;

import de.notjan.bot.guild.GuildSettingsService;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Greets a new guild and asks an admin to finish the setup in the dashboard.
 * Skipped when the bot is re-invited to an already configured guild.
 */
final class WelcomeListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(WelcomeListener.class);

    private final DashboardLinks links;
    private final GuildSettingsService settings;

    WelcomeListener(DashboardLinks links, GuildSettingsService settings) {
        this.links = links;
        this.settings = settings;
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        Guild guild = event.getGuild();
        if (settings.get(guild.getIdLong()).setupCompleted()) {
            return;
        }

        Optional<TextChannel> channel = welcomeChannel(guild);
        if (channel.isEmpty()) {
            LOG.info("No writable channel for the welcome message in guild {}", guild.getId());
            return;
        }

        var embed = new EmbedBuilder()
                .setColor(Brand.PRIMARY)
                .setTitle("Danke, dass ihr mich hinzugefügt habt! 👋")
                .setDescription("Bevor es losgeht, muss mich ein Admin einmal im Dashboard einrichten. "
                        + "Das dauert nur eine Minute." + links.inlineLink(guild))
                .build();
        channel.get().sendMessageEmbeds(embed)
                .setComponents(links.buttons(guild, "Jetzt einrichten"))
                .queue(null, error -> LOG.warn("Failed to send welcome message in guild {}", guild.getId(), error));
    }

    /** Prefers the system channel, otherwise the first text channel the bot can write to. */
    private static Optional<TextChannel> welcomeChannel(Guild guild) {
        TextChannel system = guild.getSystemChannel();
        if (system != null && system.canTalk()) {
            return Optional.of(system);
        }
        return guild.getTextChannels().stream().filter(TextChannel::canTalk).findFirst();
    }
}
