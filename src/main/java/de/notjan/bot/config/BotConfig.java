package de.notjan.bot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable runtime configuration. Values come from environment variables,
 * optionally provided through a local {@code .env} file during development.
 */
public record BotConfig(
        String discordToken,
        Optional<Long> devGuildId,
        String dashboardUrl,
        DatabaseConfig database,
        ApiConfig api
) {

    public record DatabaseConfig(String url, String user, String password, int poolSize) {
    }

    public record ApiConfig(String host, int port, String token) {
    }

    static final int MIN_API_TOKEN_LENGTH = 32;

    public static BotConfig load() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        return from(dotenv::get);
    }

    static BotConfig from(Function<String, String> env) {
        var reader = new EnvReader(env);

        String apiToken = reader.required("BOT_API_TOKEN");
        if (apiToken.length() < MIN_API_TOKEN_LENGTH) {
            throw new ConfigException("BOT_API_TOKEN must be at least " + MIN_API_TOKEN_LENGTH + " characters long");
        }

        return new BotConfig(
                reader.required("DISCORD_BOT_TOKEN"),
                reader.optional("DISCORD_DEV_GUILD_ID").map(value -> reader.parseLong("DISCORD_DEV_GUILD_ID", value)),
                stripTrailingSlash(reader.optional("DASHBOARD_URL").orElse("http://localhost:3000")),
                new DatabaseConfig(
                        reader.required("DB_URL"),
                        reader.required("DB_USER"),
                        reader.required("DB_PASSWORD"),
                        reader.integer("DB_POOL_SIZE", 5)
                ),
                new ApiConfig(
                        reader.optional("BOT_API_HOST").orElse("127.0.0.1"),
                        reader.integer("BOT_API_PORT", 8081),
                        apiToken
                )
        );
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record EnvReader(Function<String, String> env) {

        String required(String key) {
            return optional(key).orElseThrow(() -> new ConfigException("Missing required environment variable " + key));
        }

        Optional<String> optional(String key) {
            return Optional.ofNullable(env.apply(key)).map(String::strip).filter(value -> !value.isEmpty());
        }

        int integer(String key, int fallback) {
            return optional(key).map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new ConfigException(key + " must be a number, got '" + value + "'");
                }
            }).orElse(fallback);
        }

        long parseLong(String key, String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ConfigException(key + " must be a Discord ID, got '" + value + "'");
            }
        }
    }
}
