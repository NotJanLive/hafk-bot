package de.notjan.bot.guild;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.time.Duration;
import java.util.function.UnaryOperator;

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

    public void delete(long guildId) {
        cache.asMap().compute(guildId, (id, current) -> {
            repository.delete(id);
            return null;
        });
    }

    public GuildSettings update(long guildId, UnaryOperator<GuildSettings> change) {
        return cache.asMap().compute(guildId, (id, current) -> {
            GuildSettings base = current != null ? current : repository.findOrCreate(id);
            GuildSettings next = change.apply(base);
            repository.save(next);
            return next;
        });
    }
}
