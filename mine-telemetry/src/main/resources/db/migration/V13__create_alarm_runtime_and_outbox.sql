-- 告警运行状态必须与遥测 Inbox、历史、质量证据和 Outbox 在同一事务内推进。
CREATE TABLE telemetry_alarm_runtime_state (
    device_id               UUID         NOT NULL,
    product_key             VARCHAR(64)  NOT NULL,
    rule_key                VARCHAR(64)  NOT NULL,
    rule_name               VARCHAR(100) NOT NULL,
    source_type             VARCHAR(16)  NOT NULL,
    severity                VARCHAR(16)  NOT NULL,
    property_id             VARCHAR(128),
    state                   VARCHAR(32)  NOT NULL,
    alarm_occurrence_id     UUID,
    candidate_since         TIMESTAMPTZ,
    raised_at               TIMESTAMPTZ,
    recovered_at            TIMESTAMPTZ,
    cooldown_until          TIMESTAMPTZ,
    last_received_at        TIMESTAMPTZ  NOT NULL,
    last_observation        JSONB        NOT NULL DEFAULT '{}'::JSONB,
    policy_runtime_revision BIGINT       NOT NULL,
    policy_snapshot_id      UUID         NOT NULL,
    policy_content_hash     VARCHAR(71)  NOT NULL,
    rule_fingerprint        VARCHAR(71)  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_alarm_runtime_state PRIMARY KEY (device_id, rule_key),
    CONSTRAINT ck_telemetry_alarm_runtime_state_product
        CHECK (product_key ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_telemetry_alarm_runtime_state_rule
        CHECK (rule_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,63}$' AND btrim(rule_name) <> ''),
    CONSTRAINT ck_telemetry_alarm_runtime_state_source
        CHECK (source_type IN ('NUMERIC', 'QUALITY')),
    CONSTRAINT ck_telemetry_alarm_runtime_state_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_telemetry_alarm_runtime_state_property
        CHECK (property_id IS NULL OR property_id ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_telemetry_alarm_runtime_state_state CHECK (
        state IN ('NORMAL', 'FIRING_PENDING', 'ACTIVE', 'RECOVERING_PENDING', 'COOLDOWN')
    ),
    CONSTRAINT ck_telemetry_alarm_runtime_state_observation
        CHECK (jsonb_typeof(last_observation) = 'object'),
    CONSTRAINT ck_telemetry_alarm_runtime_state_policy CHECK (
        policy_runtime_revision > 0
        AND policy_content_hash ~ '^sha256:[0-9a-f]{64}$'
        AND rule_fingerprint ~ '^sha256:[0-9a-f]{64}$'
        AND version >= 0
    ),
    CONSTRAINT ck_telemetry_alarm_runtime_state_transition CHECK (
        (state = 'NORMAL'
            AND alarm_occurrence_id IS NULL AND candidate_since IS NULL
            AND raised_at IS NULL AND recovered_at IS NULL AND cooldown_until IS NULL)
        OR
        (state = 'FIRING_PENDING'
            AND alarm_occurrence_id IS NULL AND candidate_since IS NOT NULL
            AND raised_at IS NULL AND recovered_at IS NULL AND cooldown_until IS NULL)
        OR
        (state = 'ACTIVE'
            AND alarm_occurrence_id IS NOT NULL AND candidate_since IS NULL
            AND raised_at IS NOT NULL AND recovered_at IS NULL AND cooldown_until IS NULL)
        OR
        (state = 'RECOVERING_PENDING'
            AND alarm_occurrence_id IS NOT NULL AND candidate_since IS NOT NULL
            AND raised_at IS NOT NULL AND recovered_at IS NULL AND cooldown_until IS NULL)
        OR
        (state = 'COOLDOWN'
            AND alarm_occurrence_id IS NOT NULL AND candidate_since IS NULL
            AND raised_at IS NOT NULL AND recovered_at IS NOT NULL
            AND cooldown_until IS NOT NULL
            AND recovered_at >= raised_at AND cooldown_until >= recovered_at)
    )
);

CREATE INDEX idx_telemetry_alarm_runtime_state_status
    ON telemetry_alarm_runtime_state (state, severity, updated_at);

CREATE INDEX idx_telemetry_alarm_runtime_state_policy
    ON telemetry_alarm_runtime_state (policy_snapshot_id, device_id);

-- 仅允许告警业务事件进入本服务 Outbox，认领必须持有有界租约。
CREATE TABLE telemetry_outbox_event (
    event_id          UUID         NOT NULL,
    aggregate_type    VARCHAR(32)  NOT NULL,
    aggregate_id      UUID         NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    schema_version    INTEGER      NOT NULL,
    payload           JSONB        NOT NULL,
    payload_hash      VARCHAR(71)  NOT NULL,
    headers           JSONB        NOT NULL DEFAULT '{}'::JSONB,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by         VARCHAR(128),
    locked_at         TIMESTAMPTZ,
    lease_expires_at  TIMESTAMPTZ,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      TIMESTAMPTZ,
    last_error        VARCHAR(2000),
    CONSTRAINT pk_telemetry_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT uk_telemetry_outbox_event_occurrence UNIQUE (aggregate_id, event_type),
    CONSTRAINT ck_telemetry_outbox_event_aggregate
        CHECK (aggregate_type = 'ALARM_OCCURRENCE'),
    CONSTRAINT ck_telemetry_outbox_event_type
        CHECK (event_type IN ('alarm.raised', 'alarm.recovered')),
    CONSTRAINT ck_telemetry_outbox_event_schema CHECK (schema_version = 1),
    CONSTRAINT ck_telemetry_outbox_event_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload_hash ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(headers) = 'object'
    ),
    CONSTRAINT ck_telemetry_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'DEAD')
        AND attempts >= 0
    ),
    CONSTRAINT ck_telemetry_outbox_event_lease CHECK (
        (status = 'PUBLISHING'
            AND locked_by IS NOT NULL AND locked_at IS NOT NULL
            AND lease_expires_at IS NOT NULL AND lease_expires_at > locked_at)
        OR
        (status <> 'PUBLISHING'
            AND locked_by IS NULL AND locked_at IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_telemetry_outbox_event_published
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_telemetry_outbox_event_pending
    ON telemetry_outbox_event (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_telemetry_outbox_event_expired_lease
    ON telemetry_outbox_event (lease_expires_at, created_at)
    WHERE status = 'PUBLISHING';

CREATE INDEX idx_telemetry_outbox_event_published
    ON telemetry_outbox_event (published_at)
    WHERE published_at IS NOT NULL;

COMMENT ON TABLE telemetry_alarm_runtime_state IS '单设备单规则的持久告警评估当前状态';
COMMENT ON COLUMN telemetry_alarm_runtime_state.device_id IS '设备标识';
COMMENT ON COLUMN telemetry_alarm_runtime_state.product_key IS '设备所属稳定产品编码';
COMMENT ON COLUMN telemetry_alarm_runtime_state.rule_key IS '策略内稳定告警规则编码';
COMMENT ON COLUMN telemetry_alarm_runtime_state.rule_name IS '值班端使用的告警规则名称快照';
COMMENT ON COLUMN telemetry_alarm_runtime_state.source_type IS '数值或数据质量告警来源';
COMMENT ON COLUMN telemetry_alarm_runtime_state.severity IS '告警处置等级';
COMMENT ON COLUMN telemetry_alarm_runtime_state.property_id IS '规则目标物模型属性编码';
COMMENT ON COLUMN telemetry_alarm_runtime_state.state IS '当前持久告警状态机节点';
COMMENT ON COLUMN telemetry_alarm_runtime_state.alarm_occurrence_id IS '当前或冷却中告警周期 UUIDv7 标识';
COMMENT ON COLUMN telemetry_alarm_runtime_state.candidate_since IS '触发或恢复条件连续成立起点';
COMMENT ON COLUMN telemetry_alarm_runtime_state.raised_at IS '当前告警周期触发时间';
COMMENT ON COLUMN telemetry_alarm_runtime_state.recovered_at IS '当前告警周期恢复时间';
COMMENT ON COLUMN telemetry_alarm_runtime_state.cooldown_until IS '恢复后禁止再次触发的截止时间';
COMMENT ON COLUMN telemetry_alarm_runtime_state.last_received_at IS '最近参与状态推进的平台可信接收时间';
COMMENT ON COLUMN telemetry_alarm_runtime_state.last_observation IS '最近一次有界结构化观测证据';
COMMENT ON COLUMN telemetry_alarm_runtime_state.policy_runtime_revision IS '当前规则所属告警策略运行修订号';
COMMENT ON COLUMN telemetry_alarm_runtime_state.policy_snapshot_id IS '当前规则所属不可变策略快照标识';
COMMENT ON COLUMN telemetry_alarm_runtime_state.policy_content_hash IS '当前单设备告警策略内容 SHA-256';
COMMENT ON COLUMN telemetry_alarm_runtime_state.rule_fingerprint IS '当前规则规范化语义 SHA-256';
COMMENT ON COLUMN telemetry_alarm_runtime_state.version IS '并发更新使用的乐观版本号';
COMMENT ON COLUMN telemetry_alarm_runtime_state.updated_at IS '告警运行状态最后更新时间';

COMMENT ON TABLE telemetry_outbox_event IS 'Telemetry 事务内等待可靠发布的告警业务事件';
COMMENT ON COLUMN telemetry_outbox_event.event_id IS '重试保持不变的消息总线 UUIDv7 事件标识';
COMMENT ON COLUMN telemetry_outbox_event.aggregate_type IS '告警周期聚合类型';
COMMENT ON COLUMN telemetry_outbox_event.aggregate_id IS '告警周期 UUIDv7 标识';
COMMENT ON COLUMN telemetry_outbox_event.event_type IS '告警触发或恢复稳定事件类型';
COMMENT ON COLUMN telemetry_outbox_event.schema_version IS '告警事件载荷结构版本';
COMMENT ON COLUMN telemetry_outbox_event.payload IS '通过共享契约校验的完整告警事件载荷';
COMMENT ON COLUMN telemetry_outbox_event.payload_hash IS '规范化事件载荷 SHA-256';
COMMENT ON COLUMN telemetry_outbox_event.headers IS '分区键与追踪标识等有界消息头';
COMMENT ON COLUMN telemetry_outbox_event.status IS '待发布、发布中、成功、失败或死信状态';
COMMENT ON COLUMN telemetry_outbox_event.attempts IS '包含当前认领在内的发布尝试次数';
COMMENT ON COLUMN telemetry_outbox_event.next_attempt_at IS '失败后允许再次认领的时间';
COMMENT ON COLUMN telemetry_outbox_event.locked_by IS '当前持有发布租约的服务实例标识';
COMMENT ON COLUMN telemetry_outbox_event.locked_at IS '当前发布租约认领时间';
COMMENT ON COLUMN telemetry_outbox_event.lease_expires_at IS '当前发布租约到期时间';
COMMENT ON COLUMN telemetry_outbox_event.occurred_at IS '告警触发或恢复业务事实时间';
COMMENT ON COLUMN telemetry_outbox_event.created_at IS '事务内创建 Outbox 事件时间';
COMMENT ON COLUMN telemetry_outbox_event.published_at IS 'RabbitMQ 确认发布成功时间';
COMMENT ON COLUMN telemetry_outbox_event.last_error IS '脱敏截断后的最近发布错误摘要';
