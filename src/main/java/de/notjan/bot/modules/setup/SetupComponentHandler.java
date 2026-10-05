package de.notjan.bot.modules.setup;

import de.notjan.bot.audit.AuditEntry.Source;
import de.notjan.bot.core.component.ComponentHandler;
import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.guild.GuildSettings;
import de.notjan.bot.guild.GuildSettingsValidator.InvalidSettingsException;
import de.notjan.bot.guild.SettingsUpdater;
import de.notjan.bot.util.Replies;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

final class SetupComponentHandler implements ComponentHandler {

    private final SetupPanel panel;
    private final SettingsUpdater updater;

    SetupComponentHandler(SetupPanel panel, SettingsUpdater updater) {
        this.panel = panel;
        this.updater = updater;
    }

    @Override
    public String namespace() {
        return SetupModule.ID;
    }

    @Override
    public void onEntitySelect(EntitySelectInteractionEvent event, ComponentId id) {
        if (id.action().equals(SetupPanel.LOG_CHANNEL.action())) {
            Long channelId = event.getMentions().getChannels().stream().findFirst().map(ISnowflake::getIdLong).orElse(null);
            apply(event, settings -> settings.withLogChannel(channelId));
        } else if (id.action().equals(SetupPanel.DASHBOARD_ROLES.action())) {
            Set<Long> roleIds = event.getMentions().getRoles().stream().map(ISnowflake::getIdLong).collect(Collectors.toSet());
            apply(event, settings -> settings.withDashboardRoles(roleIds));
        }
    }

    @Override
    public void onButton(ButtonInteractionEvent event, ComponentId id) {
        if (id.action().equals(SetupPanel.FINISH.action())) {
            apply(event, settings -> settings.withSetupCompleted(true));
        }
    }

    private <E extends ComponentInteraction & IMessageEditCallback> void apply(E event, UnaryOperator<GuildSettings> change) {
        // The command is restricted to "Manage Server", but permissions may have changed since the panel was opened.
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            Replies.error(event, "Du brauchst die Berechtigung „Server verwalten“.");
            return;
        }

        event.deferEdit().queue();
        try {
            updater.apply(event.getGuild(), event.getUser().getIdLong(), Source.DISCORD, change);
        } catch (InvalidSettingsException e) {
            Replies.error(event, String.join("\n", e.errors()));
        }
        // Always re-render from persisted state, so a rejected selection snaps back.
        GuildSettings shown = updater.current(event.getGuild().getIdLong());
        event.getHook().editOriginal(MessageEditData.fromCreateData(panel.render(event.getGuild(), shown))).queue();
    }
}
