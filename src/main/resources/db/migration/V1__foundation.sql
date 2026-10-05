-- Core tables shared by all modules. Discord snowflakes are stored as BIGINT (they fit into a signed 64-bit long).

CREATE TABLE guild_settings
(
    guild_id        BIGINT    NOT NULL PRIMARY KEY,
    setup_completed BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE guild_dashboard_roles
(
    guild_id BIGINT NOT NULL,
    role_id  BIGINT NOT NULL,
    PRIMARY KEY (guild_id, role_id),
    CONSTRAINT fk_dashboard_roles_guild FOREIGN KEY (guild_id) REFERENCES guild_settings (guild_id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE audit_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    source     VARCHAR(16)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    summary    VARCHAR(2000) NOT NULL,
    details    LONGTEXT     NULL CHECK (details IS NULL OR JSON_VALID(details)),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_log_guild_created (guild_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
