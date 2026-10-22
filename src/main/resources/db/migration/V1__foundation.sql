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

CREATE TABLE polls
(
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id          BIGINT        NOT NULL,
    channel_id        BIGINT        NOT NULL,
    message_ref       BIGINT        NULL,
    question          VARCHAR(256)  NOT NULL,
    description       VARCHAR(1000) NULL,
    anonymous         BOOLEAN       NOT NULL,
    result_visibility VARCHAR(16)   NOT NULL,
    host_results      BOOLEAN       NOT NULL,
    max_choices       INT           NOT NULL,
    allow_change      BOOLEAN       NOT NULL,
    ping_role_id      BIGINT        NULL,
    voter_salt        CHAR(32)      NOT NULL,
    ends_at           TIMESTAMP(3)  NULL,
    closed_at         TIMESTAMP(3)  NULL,
    closed_by         BIGINT        NULL,
    cancelled         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by        BIGINT        NOT NULL,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_polls_message (message_ref),
    INDEX idx_polls_guild (guild_id),
    INDEX idx_polls_open (closed_at, ends_at),
    CONSTRAINT fk_polls_message FOREIGN KEY (message_ref) REFERENCES bot_messages (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_options
(
    id       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    poll_id  BIGINT       NOT NULL,
    label    VARCHAR(80)  NOT NULL,
    emoji    VARCHAR(100) NULL,
    position INT          NOT NULL,
    CONSTRAINT fk_poll_options_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_allowed_roles
(
    poll_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (poll_id, role_id),
    CONSTRAINT fk_poll_allowed_roles_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_votes
(
    poll_id    BIGINT       NOT NULL,
    option_id  BIGINT       NOT NULL,
    voter_key  VARCHAR(64)  NOT NULL,
    user_id    BIGINT       NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (poll_id, voter_key, option_id),
    INDEX idx_poll_votes_option (option_id),
    CONSTRAINT fk_poll_votes_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE,
    CONSTRAINT fk_poll_votes_option FOREIGN KEY (option_id) REFERENCES poll_options (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE poll_creator_roles
(
    guild_id BIGINT NOT NULL,
    role_id  BIGINT NOT NULL,
    PRIMARY KEY (guild_id, role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_settings
(
    guild_id            BIGINT       NOT NULL PRIMARY KEY,
    log_channel_id      BIGINT       NULL,
    next_number         INT          NOT NULL DEFAULT 1,
    close_delay_seconds INT          NOT NULL DEFAULT 5,
    close_request_hours INT          NOT NULL DEFAULT 24,
    creator_can_close   BOOLEAN      NOT NULL DEFAULT TRUE,
    claim_lock          BOOLEAN      NOT NULL DEFAULT FALSE,
    transfer_to_anyone  BOOLEAN      NOT NULL DEFAULT FALSE,
    notes_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    ratings_enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    dm_on_close         BOOLEAN      NOT NULL DEFAULT TRUE,
    creator_transcript  BOOLEAN      NOT NULL DEFAULT FALSE,
    retention_days      INT          NULL,
    updated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_access
(
    guild_id    BIGINT      NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    target_type VARCHAR(8)  NOT NULL,
    target_id   BIGINT      NOT NULL,
    PRIMARY KEY (guild_id, kind, target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_categories
(
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id          BIGINT        NOT NULL,
    name              VARCHAR(80)   NOT NULL,
    emoji             VARCHAR(100)  NULL,
    description       VARCHAR(100)  NULL,
    button_style      VARCHAR(16)   NOT NULL,
    parent_id         BIGINT        NULL,
    channel_pattern   VARCHAR(100)  NOT NULL,
    welcome_payload   LONGTEXT      NULL CHECK (welcome_payload IS NULL OR JSON_VALID(welcome_payload)),
    confirm_text      VARCHAR(2000) NULL,
    form_title        VARCHAR(45)   NULL,
    max_open          INT           NOT NULL DEFAULT 1,
    cooldown_minutes  INT           NOT NULL DEFAULT 0,
    ping_support      BOOLEAN       NOT NULL DEFAULT TRUE,
    include_staff     BOOLEAN       NOT NULL DEFAULT TRUE,
    application       BOOLEAN       NOT NULL DEFAULT FALSE,
    accept_role_id    BIGINT        NULL,
    accept_message    VARCHAR(1000) NULL,
    deny_message      VARCHAR(1000) NULL,
    close_on_decision BOOLEAN       NOT NULL DEFAULT FALSE,
    position          INT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_ticket_categories_guild (guild_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_category_roles
(
    category_id BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    PRIMARY KEY (category_id, kind, role_id),
    CONSTRAINT fk_ticket_category_roles_category FOREIGN KEY (category_id) REFERENCES ticket_categories (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_form_fields
(
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT       NOT NULL,
    position    INT          NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    label       VARCHAR(45)  NOT NULL,
    description VARCHAR(100) NULL,
    placeholder VARCHAR(100) NULL,
    required    BOOLEAN      NOT NULL DEFAULT TRUE,
    min_length  INT          NULL,
    max_length  INT          NULL,
    min_values  INT          NULL,
    max_values  INT          NULL,
    options     LONGTEXT     NULL CHECK (options IS NULL OR JSON_VALID(options)),
    CONSTRAINT fk_ticket_form_fields_category FOREIGN KEY (category_id) REFERENCES ticket_categories (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_panels
(
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    guild_id    BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    channel_id  BIGINT       NOT NULL,
    message_ref BIGINT       NULL,
    style       VARCHAR(16)  NOT NULL,
    placeholder VARCHAR(150) NULL,
    payload     LONGTEXT     NOT NULL CHECK (JSON_VALID(payload)),
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_ticket_panels_message (message_ref),
    INDEX idx_ticket_panels_guild (guild_id),
    CONSTRAINT fk_ticket_panels_message FOREIGN KEY (message_ref) REFERENCES bot_messages (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_panel_categories
(
    panel_id    BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    position    INT    NOT NULL,
    PRIMARY KEY (panel_id, category_id),
    CONSTRAINT fk_ticket_panel_categories_panel FOREIGN KEY (panel_id) REFERENCES ticket_panels (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_panel_categories_category FOREIGN KEY (category_id) REFERENCES ticket_categories (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tickets
(
    id                       BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid                     CHAR(36)      NOT NULL,
    guild_id                 BIGINT        NOT NULL,
    number                   INT           NOT NULL,
    category_id              BIGINT        NULL,
    category_name            VARCHAR(80)   NOT NULL,
    channel_id               BIGINT        NULL,
    channel_name             VARCHAR(100)  NOT NULL,
    notes_thread_id          BIGINT        NULL,
    control_message_id       BIGINT        NULL,
    answers_message_id       BIGINT        NULL,
    answers                  LONGTEXT      NULL CHECK (answers IS NULL OR JSON_VALID(answers)),
    creator_id               BIGINT        NOT NULL,
    creator_name             VARCHAR(100)  NOT NULL,
    opened_by                BIGINT        NOT NULL,
    claimed_by               BIGINT        NULL,
    status                   VARCHAR(16)   NOT NULL,
    application_state        VARCHAR(16)   NULL,
    close_request_by         BIGINT        NULL,
    close_request_reason     VARCHAR(1000) NULL,
    close_request_until      TIMESTAMP(3)  NULL,
    close_request_message_id BIGINT        NULL,
    first_response_at        TIMESTAMP(3)  NULL,
    created_at               TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at                TIMESTAMP(3)  NULL,
    closed_by                BIGINT        NULL,
    close_reason             VARCHAR(1000) NULL,
    UNIQUE KEY uq_tickets_uuid (uuid),
    UNIQUE KEY uq_tickets_number (guild_id, number),
    INDEX idx_tickets_guild_status (guild_id, status),
    INDEX idx_tickets_channel (channel_id),
    INDEX idx_tickets_creator (guild_id, creator_id),
    CONSTRAINT fk_tickets_category FOREIGN KEY (category_id) REFERENCES ticket_categories (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_members
(
    ticket_id   BIGINT       NOT NULL,
    target_type VARCHAR(8)   NOT NULL,
    target_id   BIGINT       NOT NULL,
    added_by    BIGINT       NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (ticket_id, target_type, target_id),
    CONSTRAINT fk_ticket_members_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_events
(
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id  BIGINT        NOT NULL,
    type       VARCHAR(32)   NOT NULL,
    actor_id   BIGINT        NOT NULL,
    target_id  BIGINT        NULL,
    detail     VARCHAR(1000) NULL,
    created_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_ticket_events_ticket (ticket_id, created_at),
    CONSTRAINT fk_ticket_events_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_messages
(
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id  BIGINT       NOT NULL,
    message_id BIGINT       NOT NULL,
    author_id  BIGINT       NOT NULL,
    internal   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL,
    edited_at  TIMESTAMP(3) NULL,
    deleted_at TIMESTAMP(3) NULL,
    data       LONGTEXT     NOT NULL CHECK (JSON_VALID(data)),
    UNIQUE KEY uq_ticket_messages_message (message_id),
    INDEX idx_ticket_messages_ticket (ticket_id, created_at),
    CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_assets
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id    BIGINT       NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    asset_key    VARCHAR(100) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NULL,
    size         BIGINT       NOT NULL DEFAULT 0,
    storage_path VARCHAR(500) NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_ticket_assets_key (ticket_id, kind, asset_key),
    CONSTRAINT fk_ticket_assets_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_ratings
(
    ticket_id    BIGINT        NOT NULL PRIMARY KEY,
    guild_id     BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    supporter_id BIGINT        NULL,
    stars        INT           NOT NULL,
    comment      VARCHAR(1000) NULL,
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_ticket_ratings_supporter (guild_id, supporter_id),
    CONSTRAINT fk_ticket_ratings_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE ticket_blacklist
(
    guild_id    BIGINT       NOT NULL,
    target_type VARCHAR(8)   NOT NULL,
    target_id   BIGINT       NOT NULL,
    reason      VARCHAR(500) NULL,
    expires_at  TIMESTAMP(3) NULL,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (guild_id, target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
