package de.notjan.bot.core.component;

import java.util.Arrays;
import java.util.List;

/**
 * Structured custom ID for buttons, select menus and modals: {@code namespace:action[:arg...]}.
 * Discord limits custom IDs to 100 characters.
 */
public record ComponentId(String namespace, String action, List<String> args) {

    private static final String SEPARATOR = ":";
    private static final int MAX_LENGTH = 100;

    public ComponentId {
        args = List.copyOf(args);
    }

    public static ComponentId of(String namespace, String action, Object... args) {
        return new ComponentId(namespace, action, Arrays.stream(args).map(String::valueOf).toList());
    }

    public static ComponentId parse(String raw) {
        String[] parts = raw.split(SEPARATOR);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid component id: " + raw);
        }
        return new ComponentId(parts[0], parts[1], List.of(parts).subList(2, parts.length));
    }

    public String arg(int index) {
        return args.get(index);
    }

    public long longArg(int index) {
        return Long.parseLong(args.get(index));
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(namespace).append(SEPARATOR).append(action);
        args.forEach(arg -> builder.append(SEPARATOR).append(arg));
        if (builder.length() > MAX_LENGTH) {
            throw new IllegalStateException("Component id exceeds " + MAX_LENGTH + " characters: " + builder);
        }
        return builder.toString();
    }
}
