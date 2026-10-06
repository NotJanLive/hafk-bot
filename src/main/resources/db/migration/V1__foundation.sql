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

CREATE TABLE bot_messages
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id   BIGINT       NOT NULL,
    channel_id BIGINT       NOT NULL,
    message_id BIGINT       NOT NULL,
    module     VARCHAR(32)  NOT NULL,
    label      VARCHAR(100) NOT NULL,
    payload    LONGTEXT     NOT NULL CHECK (JSON_VALID(payload)),
    created_by BIGINT       NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_bot_messages_message (message_id),
    INDEX idx_bot_messages_guild_module (guild_id, module)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE bot_channels
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id   BIGINT       NOT NULL,
    channel_id BIGINT       NOT NULL,
    module     VARCHAR(32)  NOT NULL,
    label      VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_bot_channels_channel (channel_id),
    INDEX idx_bot_channels_guild (guild_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE embed_templates
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id   BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    payload    LONGTEXT     NOT NULL CHECK (JSON_VALID(payload)),
    created_by BIGINT       NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_embed_templates_name (guild_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE reaction_role_panels
(
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id    BIGINT       NOT NULL,
    message_ref BIGINT       NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    mode        VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_reaction_role_panels_message (message_ref),
    INDEX idx_reaction_role_panels_guild (guild_id),
    CONSTRAINT fk_reaction_role_panels_message FOREIGN KEY (message_ref) REFERENCES bot_messages (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE reaction_role_options
(
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    panel_id    BIGINT       NOT NULL,
    role_id     BIGINT       NOT NULL,
    label       VARCHAR(80)  NULL,
    emoji       VARCHAR(100) NULL,
    description VARCHAR(100) NULL,
    style       VARCHAR(16)  NULL,
    position    INT          NOT NULL,
    UNIQUE KEY uq_reaction_role_options_role (panel_id, role_id),
    CONSTRAINT fk_reaction_role_options_panel FOREIGN KEY (panel_id) REFERENCES reaction_role_panels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
