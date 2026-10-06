package de.notjan.bot.modules.setup;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;

final class DashboardLinks {

    private final String dashboardUrl;

    DashboardLinks(String dashboardUrl) {
        this.dashboardUrl = dashboardUrl;
    }

    String guildUrl(Guild guild) {
        return dashboardUrl + "/servers/" + guild.getId();
    }

    boolean supportsButtons() {
        return dashboardUrl.startsWith("https://");
    }

    String inlineLink(Guild guild) {
        return supportsButtons() ? "" : "\n\n**Dashboard:** " + guildUrl(guild);
    }

    List<ActionRow> buttons(Guild guild, String label) {
        return supportsButtons() ? List.of(ActionRow.of(Button.link(guildUrl(guild), label))) : List.of();
    }
}
