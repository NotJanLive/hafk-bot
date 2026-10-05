package de.notjan.bot.util;

import java.awt.Color;

/**
 * Shared visual identity for embeds and panels. Keep in sync with the dashboard theme tokens.
 */
public final class Brand {

    public static final String NAME = "HAFK";

    public static final Color PRIMARY = new Color(0x3DDC97);
    public static final Color INFO = new Color(0x5AA9FF);
    public static final Color WARNING = new Color(0xFFB547);
    public static final Color DANGER = new Color(0xFF5C6C);

    private Brand() {
    }
}
