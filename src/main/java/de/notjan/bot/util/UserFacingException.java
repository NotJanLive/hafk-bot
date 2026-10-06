package de.notjan.bot.util;

import java.util.List;

public class UserFacingException extends RuntimeException {

    private final List<String> errors;
    private final boolean notFound;

    public UserFacingException(String message) {
        this(List.of(message), false);
    }

    public UserFacingException(List<String> errors) {
        this(errors, false);
    }

    private UserFacingException(List<String> errors, boolean notFound) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
        this.notFound = notFound;
    }

    public static UserFacingException notFound(String message) {
        return new UserFacingException(List.of(message), true);
    }

    public List<String> errors() {
        return errors;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
