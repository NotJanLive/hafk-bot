package de.notjan.bot.util;

import java.awt.Color;

/**
 * Shared visual identity for embeds and panels. Keep in sync with the dashboard theme tokens.
 */
public final class Brand {

    public static final String NAME = "HAFK-Bot";

    public static final Color PRIMARY = new Color(0x5B6CFF);
    public static final Color WARNING = new Color(0xF5A524);
    public static final Color DANGER = new Color(0xEF4D5A);

    private Brand() {
    }
}
