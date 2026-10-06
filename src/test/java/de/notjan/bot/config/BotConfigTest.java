package de.notjan.bot.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotConfigTest {

    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("BOT_TOKEN", "token");
        env.put("DB_URL", "jdbc:mariadb://localhost:3306/hafk");
        env.put("DB_USER", "hafk");
        env.put("DB_PASSWORD", "secret");
        env.put("SHARED_SECRET", "a".repeat(BotConfig.MIN_API_TOKEN_LENGTH));
        return env;
    }

    @Test
    void appliesDefaults() {
        BotConfig config = BotConfig.from(validEnv()::get);

        assertEquals("127.0.0.1", config.api().host());
        assertEquals(8081, config.api().port());
        assertEquals(5, config.database().poolSize());
        assertEquals("http://localhost:3000", config.dashboardUrl());
        assertTrue(config.devGuildId().isEmpty());
    }

    @Test
    void readsOptionalValues() {
        Map<String, String> env = validEnv();
        env.put("DISCORD_DEV_GUILD_ID", "123456789012345678");
        env.put("DASHBOARD_URL", "https://dash.example.org/");
        env.put("BOT_API_PORT", "9000");

        BotConfig config = BotConfig.from(env::get);

        assertEquals(Optional.of(123456789012345678L), config.devGuildId());
        assertEquals("https://dash.example.org", config.dashboardUrl());
        assertEquals(9000, config.api().port());
    }

    @Test
    void failsOnMissingRequiredValue() {
        Map<String, String> env = validEnv();
        env.remove("BOT_TOKEN");

        var error = assertThrows(ConfigException.class, () -> BotConfig.from(env::get));
        assertTrue(error.getMessage().contains("BOT_TOKEN"));
    }

    @Test
    void treatsBlankValuesAsMissing() {
        Map<String, String> env = validEnv();
        env.put("DB_PASSWORD", "   ");

        assertThrows(ConfigException.class, () -> BotConfig.from(env::get));
    }

    @Test
    void rejectsShortApiToken() {
        Map<String, String> env = validEnv();
        env.put("SHARED_SECRET", "too-short");

        assertThrows(ConfigException.class, () -> BotConfig.from(env::get));
    }

    @Test
    void rejectsInvalidNumbers() {
        Map<String, String> env = validEnv();
        env.put("BOT_API_PORT", "eighty");

        assertThrows(ConfigException.class, () -> BotConfig.from(env::get));
    }
}
