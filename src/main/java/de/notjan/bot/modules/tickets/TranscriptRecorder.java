package de.notjan.bot.modules.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.notjan.bot.modules.tickets.TranscriptRepository.StoredMessage;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

final class TranscriptRecorder extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptRecorder.class);
    private static final int MAX_HISTORY = 10_000;

    record Channel(long ticketId, long creatorId, boolean internal) {
    }

    private final TranscriptRepository repository;
    private final TranscriptSnapshot snapshot;
    private final ObjectMapper json;
    private final LongFunction<Ticket> tickets;
    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> responded = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ticket-transcripts").daemon().factory());
    private volatile FirstResponseListener firstResponse = (ticketId, at) -> { };

    interface FirstResponseListener {
        void onFirstResponse(long ticketId, Instant at);
    }

    TranscriptRecorder(TranscriptRepository repository, TranscriptSnapshot snapshot, ObjectMapper json, LongFunction<Ticket> tickets) {
        this.repository = repository;
        this.snapshot = snapshot;
        this.json = json;
        this.tickets = tickets;
    }

    void onFirstResponse(FirstResponseListener listener) {
        this.firstResponse = listener;
    }

    void track(long channelId, long ticketId, long creatorId, boolean internal, boolean answered) {
        channels.put(channelId, new Channel(ticketId, creatorId, internal));
        if (answered) {
            responded.put(ticketId, true);
        }
    }

    void untrack(long ticketId) {
        channels.values().removeIf(channel -> channel.ticketId() == ticketId);
        responded.remove(ticketId);
    }

    boolean isTracked(long channelId) {
        return channels.containsKey(channelId);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Channel channel = channels.get(event.getChannel().getIdLong());
        if (channel == null) {
            return;
        }
        Message message = event.getMessage();
        executor.execute(() -> record(channel, message));
        if (!channel.internal() && !message.getAuthor().isBot() && message.getAuthor().getIdLong() != channel.creatorId()
                && responded.putIfAbsent(channel.ticketId(), true) == null) {
            firstResponse.onFirstResponse(channel.ticketId(), message.getTimeCreated().toInstant());
        }
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        Channel channel = channels.get(event.getChannel().getIdLong());
        if (channel == null) {
            return;
        }
        Message message = event.getMessage();
        executor.execute(() -> record(channel, message));
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (!channels.containsKey(event.getChannel().getIdLong())) {
            return;
        }
        long messageId = event.getMessageIdLong();
        Instant now = Instant.now();
        executor.execute(() -> repository.markDeleted(List.of(messageId), now));
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        if (!channels.containsKey(event.getChannel().getIdLong())) {
            return;
        }
        List<Long> ids = event.getMessageIds().stream().map(Long::parseLong).toList();
        Instant now = Instant.now();
        executor.execute(() -> repository.markDeleted(ids, now));
    }

    void sync(Ticket ticket, GuildMessageChannel channel, boolean internal) {
        List<Message> history;
        try {
            history = channel.getIterableHistory().takeAsync(MAX_HISTORY).get();
        } catch (Exception e) {
            LOG.warn("Could not load history of ticket {} channel {}", ticket.id(), channel.getId(), e);
            return;
        }
        Channel target = new Channel(ticket.id(), ticket.creatorId(), internal);
        for (Message message : history.reversed()) {
            try {
                executor.submit(() -> record(target, message)).get();
            } catch (Exception e) {
                LOG.warn("Could not store message {} of ticket {}", message.getId(), ticket.id(), e);
            }
        }
    }

    void flush() {
        try {
            executor.submit(() -> { }).get();
        } catch (Exception e) {
            LOG.debug("Waiting for transcript writes failed", e);
        }
    }

    private void record(Channel channel, Message message) {
        try {
            Ticket ticket = tickets.apply(channel.ticketId());
            if (ticket == null) {
                return;
            }
            ObjectNode next = snapshot.capture(ticket, message);
            OffsetDateTime edited = message.getTimeEdited();
            var existing = repository.message(message.getIdLong());
            if (existing.isEmpty()) {
                next.putArray("edits");
                repository.insertMessage(ticket.id(), message.getIdLong(), message.getAuthor().getIdLong(), channel.internal(),
                        message.getTimeCreated().toInstant(), edited == null ? null : edited.toInstant(), json.writeValueAsString(next));
                return;
            }
            StoredMessage stored = existing.get();
            JsonNode previous = json.readTree(stored.data());
            repository.updateMessage(message.getIdLong(), edited == null ? stored.editedAt() : edited.toInstant(),
                    json.writeValueAsString(snapshot.merge(previous, next)));
        } catch (Exception e) {
            LOG.warn("Could not record message {} for ticket {}", message.getId(), channel.ticketId(), e);
        }
    }
}
