package de.notjan.bot.modules.polls;

import de.notjan.bot.util.UserFacingException;

import java.util.LinkedHashSet;
import java.util.Set;

final class VoteRules {

    private VoteRules() {
    }

    static Set<Long> click(int maxChoices, boolean allowChange, Set<Long> current, long optionId) {
        if (current.contains(optionId)) {
            if (!allowChange) {
                throw new UserFacingException("Deine Stimme ist abgegeben und kann bei dieser Umfrage nicht geändert werden.");
            }
            Set<Long> next = new LinkedHashSet<>(current);
            next.remove(optionId);
            return Set.copyOf(next);
        }
        if (maxChoices == 1) {
            if (!current.isEmpty() && !allowChange) {
                throw new UserFacingException("Du hast bereits abgestimmt. Bei dieser Umfrage kann die Stimme nicht geändert werden.");
            }
            return Set.of(optionId);
        }
        if (current.size() >= maxChoices) {
            throw new UserFacingException(allowChange
                    ? "Du kannst höchstens " + maxChoices + " Antworten wählen. Klicke eine gewählte Antwort erneut an, um sie zu entfernen."
                    : "Du hast bereits " + maxChoices + " Antworten gewählt.");
        }
        Set<Long> next = new LinkedHashSet<>(current);
        next.add(optionId);
        return Set.copyOf(next);
    }

    static Set<Long> withdraw(boolean allowChange, Set<Long> current) {
        if (current.isEmpty()) {
            throw new UserFacingException("Du hast bei dieser Umfrage noch nicht abgestimmt.");
        }
        if (!allowChange) {
            throw new UserFacingException("Bei dieser Umfrage kann die Stimme nicht zurückgezogen werden.");
        }
        return Set.of();
    }
}
