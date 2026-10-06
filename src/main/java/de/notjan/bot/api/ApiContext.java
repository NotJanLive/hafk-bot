package de.notjan.bot.api;

import net.dv8tion.jda.api.JDA;

public record ApiContext(JDA jda, GuildGuard guard) {
}
