package de.notjan.bot.modules.tickets;

import de.notjan.bot.modules.tickets.TicketConfig.TargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record Ticket(long id, String uuid, long guildId, int number, Long categoryId, String categoryName, Long channelId, String channelName,
                     Long notesThreadId, Long controlMessageId, Long answersMessageId, List<Answer> answers, long creatorId,
                     String creatorName, long openedBy, Long claimedBy, Status status, ApplicationState applicationState,
                     Long closeRequestBy, String closeRequestReason, Instant closeRequestUntil, Long closeRequestMessageId,
                     Instant firstResponseAt, Instant createdAt, Instant closedAt, Long closedBy, String closeReason) {

    public enum Status {
        OPEN,
        CLOSED
    }

    public enum ApplicationState {
        PENDING,
        ACCEPTED,
        DENIED
    }

    public enum EventType {
        OPENED,
        CLAIMED,
        UNCLAIMED,
        TRANSFERRED,
        MEMBER_ADDED,
        MEMBER_REMOVED,
        RENAMED,
        MOVED,
        CLOSE_REQUESTED,
        CLOSE_REQUEST_CANCELLED,
        ACCEPTED,
        DENIED,
        CLOSED
    }

    public record Answer(String question, String answer) {
    }

    public record Event(long id, EventType type, long actorId, Long targetId, String detail, Instant createdAt) {
    }

    public record Member(TargetType type, long targetId, long addedBy, Instant createdAt) {
    }

    public Ticket {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public String label() {
        return "#" + number + " " + categoryName;
    }

    public Duration duration() {
        return Duration.between(createdAt, closedAt == null ? Instant.now() : closedAt);
    }
}
