package de.notjan.bot.reset;

import java.util.function.LongConsumer;
import java.util.function.LongUnaryOperator;

public interface ResettableData {

    String id();

    String label();

    String description();

    long count(long guildId);

    void delete(long guildId);

    boolean required();

    static ResettableData of(String id, String label, String description, LongUnaryOperator count, LongConsumer delete) {
        return new Simple(id, label, description, false, count, delete);
    }

    static ResettableData required(String id, String label, String description, LongUnaryOperator count, LongConsumer delete) {
        return new Simple(id, label, description, true, count, delete);
    }

    record Simple(String id, String label, String description, boolean required, LongUnaryOperator counter, LongConsumer deleter)
            implements ResettableData {

        @Override
        public long count(long guildId) {
            return counter.applyAsLong(guildId);
        }

        @Override
        public void delete(long guildId) {
            deleter.accept(guildId);
        }
    }
}
