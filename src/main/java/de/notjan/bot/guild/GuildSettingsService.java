package de.notjan.bot.guild;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Cached access to {@link GuildSettings}. All writes go through {@link #update} so the cache never
 * diverges from the database.
 */
public final class GuildSettingsService {

    private final GuildSettingsRepository repository;
    private final LoadingCache<Long, GuildSettings> cache;

    public GuildSettingsService(GuildSettingsRepository repository) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build(repository::findOrCreate);
    }

    public GuildSettings get(long guildId) {
        return cache.get(guildId);
    }

    /** Applies a change atomically per guild and persists it before the cache is updated. */
    public GuildSettings update(long guildId, UnaryOperator<GuildSettings> change) {
        return cache.asMap().compute(guildId, (id, current) -> {
            GuildSettings base = current != null ? current : repository.findOrCreate(id);
            GuildSettings next = change.apply(base);
            repository.save(next);
            return next;
        });
    }
}
