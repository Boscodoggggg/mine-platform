-- 登录审计记录保留用户名快照，用户删除后仍可独立追溯。
CREATE TABLE sys_login_log (
    id                UUID         NOT NULL,
    user_id           UUID,
    username_snapshot VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(32)  NOT NULL,
    result            VARCHAR(32)  NOT NULL,
    error_code        VARCHAR(64),
    request_id        VARCHAR(64),
    trace_id          VARCHAR(64)  NOT NULL,
    client_ip         INET,
    user_agent        VARCHAR(512),
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_login_log PRIMARY KEY (id),
    CONSTRAINT ck_sys_login_log_event_type CHECK (event_type IN ('LOGIN', 'LOGOUT', 'LOGIN_FAILURE')),
    CONSTRAINT ck_sys_login_log_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_sys_login_log_user_time
    ON sys_login_log (user_id, occurred_at DESC);

CREATE INDEX idx_sys_login_log_trace
    ON sys_login_log (trace_id);

-- 操作审计仅追加，不设置 updated_at；before/after 只保存脱敏摘要或差异。
CREATE TABLE sys_operation_log (
    id                UUID         NOT NULL,
    operator_id       UUID,
    username_snapshot VARCHAR(64),
    module            VARCHAR(64)  NOT NULL,
    action            VARCHAR(64)  NOT NULL,
    permission        VARCHAR(128),
    resource_type     VARCHAR(64),
    resource_id       VARCHAR(128),
    request_id        VARCHAR(64),
    trace_id          VARCHAR(64)  NOT NULL,
    client_ip         INET,
    user_agent        VARCHAR(512),
    result            VARCHAR(32)  NOT NULL,
    error_code        VARCHAR(64),
    duration_ms       BIGINT       NOT NULL,
    before_summary    JSONB,
    after_summary     JSONB,
    reason            VARCHAR(1000),
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_operation_log PRIMARY KEY (id),
    CONSTRAINT ck_sys_operation_log_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_sys_operation_log_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_sys_operation_log_before CHECK (
        before_summary IS NULL OR jsonb_typeof(before_summary) = 'object'
    ),
    CONSTRAINT ck_sys_operation_log_after CHECK (
        after_summary IS NULL OR jsonb_typeof(after_summary) = 'object'
    )
);

CREATE INDEX idx_sys_operation_log_operator_time
    ON sys_operation_log (operator_id, occurred_at DESC);

CREATE INDEX idx_sys_operation_log_resource_time
    ON sys_operation_log (resource_type, resource_id, occurred_at DESC);

CREATE INDEX idx_sys_operation_log_trace
    ON sys_operation_log (trace_id);

-- 业务事务与待发布事件写入同一个 PostgreSQL 事务。
CREATE TABLE sys_outbox_event (
    id               UUID         NOT NULL,
    aggregate_type   VARCHAR(128) NOT NULL,
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    schema_version   INTEGER      NOT NULL,
    payload          JSONB        NOT NULL,
    headers          JSONB        NOT NULL DEFAULT '{}'::JSONB,
    status           VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at     TIMESTAMPTZ,
    last_error       VARCHAR(2000),
    CONSTRAINT pk_sys_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_sys_outbox_event_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_sys_outbox_event_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_sys_outbox_event_headers CHECK (jsonb_typeof(headers) = 'object'),
    CONSTRAINT ck_sys_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'DEAD')
    ),
    CONSTRAINT ck_sys_outbox_event_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_sys_outbox_event_published_at CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL
    )
);

CREATE INDEX idx_sys_outbox_event_pending
    ON sys_outbox_event (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_sys_outbox_event_aggregate
    ON sys_outbox_event (aggregate_type, aggregate_id, created_at);

CREATE INDEX idx_sys_outbox_event_published_at
    ON sys_outbox_event (published_at)
    WHERE published_at IS NOT NULL;

-- 消费方名称和事件 ID 共同保证每个消费者只处理一次同一事件。
CREATE TABLE sys_inbox_event (
    consumer_name VARCHAR(128) NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result        VARCHAR(32)  NOT NULL,
    error_code    VARCHAR(64),
    CONSTRAINT pk_sys_inbox_event PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_sys_inbox_event_result CHECK (result IN ('SUCCESS', 'FAILURE', 'IGNORED'))
);

CREATE INDEX idx_sys_inbox_event_processed_at
    ON sys_inbox_event (processed_at);

COMMENT ON TABLE sys_login_log IS '登录、退出和登录失败审计日志';
COMMENT ON TABLE sys_operation_log IS '敏感管理操作的不可变审计日志';
COMMENT ON TABLE sys_outbox_event IS '事务性消息待发布事件';
COMMENT ON TABLE sys_inbox_event IS '消息消费幂等记录';
