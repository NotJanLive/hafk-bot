package de.notjan.bot.util;

public enum ConfigKey {
    TOKEN("Token"),
    BOTID("BotID");

    public final String name;

    ConfigKey(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return this.name;
    }

}