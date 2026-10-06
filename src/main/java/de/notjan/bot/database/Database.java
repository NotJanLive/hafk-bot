package de.notjan.bot.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.notjan.bot.config.BotConfig.DatabaseConfig;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbi = Jdbi.create(dataSource);
    }

    public static Database connect(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("hafk-db");
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());

        HikariDataSource dataSource = new HikariDataSource(hikari);
        try {
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            LOG.info("Database ready, applied {} migration(s), schema version {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new Database(dataSource);
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
