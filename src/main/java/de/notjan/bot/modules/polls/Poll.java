package de.notjan.bot.modules.polls;

import de.notjan.bot.message.BotMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record Poll(
        long id,
        long guildId,
        long channelId,
        BotMessage message,
        String question,
        String description,
        boolean anonymous,
        ResultVisibility visibility,
        boolean hostResults,
        int maxChoices,
        boolean allowChange,
        Long pingRoleId,
        String voterSalt,
        Instant endsAt,
        Instant closedAt,
        Long closedBy,
        boolean cancelled,
        long createdBy,
        Instant createdAt,
        List<Option> options,
        Set<Long> allowedRoleIds
) {

    public enum ResultVisibility {
        LIVE,
        AFTER_VOTE,
        CLOSED
    }

    public record Option(long id, String label, String emoji) {
    }

    public record OptionDraft(String label, String emoji) {
    }

    public record Draft(
            long channelId,
            String question,
            String description,
            boolean anonymous,
            ResultVisibility visibility,
            boolean hostResults,
            int maxChoices,
            boolean allowChange,
            Long pingRoleId,
            Integer durationMinutes,
            List<OptionDraft> options,
            Set<Long> allowedRoleIds
    ) {
    }

    public Poll {
        options = List.copyOf(options);
        allowedRoleIds = Set.copyOf(allowedRoleIds);
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    public Optional<Option> option(long optionId) {
        return options.stream().filter(option -> option.id() == optionId).findFirst();
    }

    public boolean membersSeeResults(boolean hasVoted) {
        if (cancelled) {
            return false;
        }
        return !isOpen() || visibility == ResultVisibility.LIVE || (visibility == ResultVisibility.AFTER_VOTE && hasVoted);
    }

    public boolean managersSeeResults() {
        return !isOpen() || hostResults || visibility == ResultVisibility.LIVE;
    }
}
