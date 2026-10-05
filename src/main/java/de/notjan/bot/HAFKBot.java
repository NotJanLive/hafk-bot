package de.notjan.bot;

import de.notjan.bot.util.Config;
import de.notjan.bot.util.ConfigKey;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import java.util.EnumSet;

public class HAFKBot {

    public static void main(String[] args) {
        Config config = new Config("config.yml");

        JDA jda = JDABuilder.createLight((String) config.get(ConfigKey.TOKEN), EnumSet.noneOf(GatewayIntent.class))
                .setStatus(OnlineStatus.ONLINE)
                .enableIntents(
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGE_REACTIONS
                )
                .build();

        CommandListUpdateAction commands = jda.updateCommands();

        commands.addCommands().queue();

        commands.queue();
    }
}
