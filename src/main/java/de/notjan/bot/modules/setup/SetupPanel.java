package de.notjan.bot.modules.setup;

import de.notjan.bot.core.component.ComponentId;
import de.notjan.bot.guild.GuildSettings;
import de.notjan.bot.guild.GuildSettingsValidator;
import de.notjan.bot.util.Brand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.DefaultValue;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the /setup panel (Components V2). Re-rendered after every change so it always shows
 * the persisted state.
 */
final class SetupPanel {

    static final ComponentId LOG_CHANNEL = ComponentId.of(SetupModule.ID, "log-channel");
    static final ComponentId DASHBOARD_ROLES = ComponentId.of(SetupModule.ID, "dashboard-roles");
    static final ComponentId FINISH = ComponentId.of(SetupModule.ID, "finish");

    private final String dashboardUrl;

    SetupPanel(String dashboardUrl) {
        this.dashboardUrl = dashboardUrl;
    }

    MessageCreateData render(Guild guild, GuildSettings settings) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("## ⚙️ Server-Setup\nRichte **" + guild.getName() + "** ein. "
                + "Alle Einstellungen findest du auch im Dashboard."));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        children.add(TextDisplay.of("### 📝 Log-Kanal\nHier protokolliert der Bot jede Änderung an der Konfiguration."));
        children.add(ActionRow.of(logChannelMenu(settings)));

        children.add(TextDisplay.of("### 🛡️ Dashboard-Zugriff\nAdmins und Mitglieder mit „Server verwalten“ haben immer Zugriff. "
                + "Zusätzlich dürfen diese Rollen das Dashboard nutzen:"));
        children.add(ActionRow.of(dashboardRoleMenu(settings)));

        children.add(Separator.createDivider(Separator.Spacing.LARGE));
        children.add(TextDisplay.of(settings.setupCompleted()
                ? "✅ **Setup abgeschlossen.** Du kannst die Einstellungen jederzeit anpassen."
                : "⏳ **Setup offen.** Wähle die Einstellungen und schließe das Setup ab."));
        children.add(ActionRow.of(actionButtons(guild, settings)));

        Container container = Container.of(children).withAccentColor(Brand.PRIMARY);
        return new MessageCreateBuilder().useComponentsV2().setComponents(container).build();
    }

    private static EntitySelectMenu logChannelMenu(GuildSettings settings) {
        var menu = EntitySelectMenu.create(LOG_CHANNEL.toString(), SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Kein Log-Kanal")
                .setRequiredRange(0, 1);
        settings.logChannel().ifPresent(id -> menu.setDefaultValues(DefaultValue.channel(id)));
        return menu.build();
    }

    private static EntitySelectMenu dashboardRoleMenu(GuildSettings settings) {
        return EntitySelectMenu.create(DASHBOARD_ROLES.toString(), SelectTarget.ROLE)
                .setPlaceholder("Keine zusätzlichen Rollen")
                .setRequiredRange(0, GuildSettingsValidator.MAX_DASHBOARD_ROLES)
                .setDefaultValues(settings.dashboardRoleIds().stream().map(DefaultValue::role).toList())
                .build();
    }

    private List<Button> actionButtons(Guild guild, GuildSettings settings) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.success(FINISH.toString(), "Setup abschließen").withDisabled(settings.setupCompleted()));
        // Discord only accepts link buttons with public https URLs, so skip it for local development.
        if (dashboardUrl.startsWith("https://")) {
            buttons.add(Button.link(dashboardUrl + "/servers/" + guild.getId(), "Im Dashboard öffnen"));
        }
        return buttons;
    }
}
