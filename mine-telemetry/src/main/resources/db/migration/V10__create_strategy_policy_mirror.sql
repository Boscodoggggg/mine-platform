-- 数据质量策略先进入可重试暂存区，完整性校验通过后再原子切换持久本地镜像。
CREATE TABLE telemetry_strategy_snapshot_stage (
    snapshot_id   UUID        NOT NULL,
    device_id     UUID        NOT NULL,
    values_json   JSONB       NOT NULL,
    content_hash  VARCHAR(71) NOT NULL,
    staged_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_strategy_snapshot_stage PRIMARY KEY (snapshot_id, device_id),
    CONSTRAINT ck_telemetry_strategy_snapshot_stage_values
        CHECK (jsonb_typeof(values_json) = 'object'),
    CONSTRAINT ck_telemetry_strategy_snapshot_stage_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_telemetry_strategy_snapshot_stage_time
    ON telemetry_strategy_snapshot_stage (staged_at);

CREATE TABLE telemetry_strategy_runtime_revision (
    product_id        UUID        NOT NULL,
    strategy_type     VARCHAR(64) NOT NULL,
    runtime_revision  BIGINT      NOT NULL,
    snapshot_id       UUID        NOT NULL,
    item_count        INTEGER     NOT NULL,
    content_hash      VARCHAR(71) NOT NULL,
    applied_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_strategy_runtime_revision PRIMARY KEY (product_id, strategy_type),
    CONSTRAINT uk_telemetry_strategy_runtime_revision_snapshot UNIQUE (snapshot_id),
    CONSTRAINT ck_telemetry_strategy_runtime_revision_type
        CHECK (strategy_type IN ('DATA_QUALITY')),
    CONSTRAINT ck_telemetry_strategy_runtime_revision_number CHECK (runtime_revision > 0),
    CONSTRAINT ck_telemetry_strategy_runtime_revision_count CHECK (item_count >= 0),
    CONSTRAINT ck_telemetry_strategy_runtime_revision_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE telemetry_data_quality_policy (
    device_id         UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    runtime_revision  BIGINT      NOT NULL,
    snapshot_id       UUID        NOT NULL,
    values_json       JSONB       NOT NULL,
    content_hash      VARCHAR(71) NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_data_quality_policy PRIMARY KEY (device_id),
    CONSTRAINT ck_telemetry_data_quality_policy_revision CHECK (runtime_revision > 0),
    CONSTRAINT ck_telemetry_data_quality_policy_values CHECK (
        jsonb_typeof(values_json) = 'object' AND values_json <> '{}'::JSONB
    ),
    CONSTRAINT ck_telemetry_data_quality_policy_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_telemetry_data_quality_policy_product
    ON telemetry_data_quality_policy (product_id, device_id);

CREATE TABLE telemetry_strategy_event_inbox (
    event_id     UUID        NOT NULL,
    snapshot_id  UUID        NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_strategy_event_inbox PRIMARY KEY (event_id)
);

CREATE INDEX idx_telemetry_strategy_event_inbox_received
    ON telemetry_strategy_event_inbox (received_at);

COMMENT ON TABLE telemetry_strategy_snapshot_stage IS '尚未完成整包校验的策略运行快照暂存条目';
COMMENT ON COLUMN telemetry_strategy_snapshot_stage.snapshot_id IS '服务端不可变策略运行快照标识';
COMMENT ON COLUMN telemetry_strategy_snapshot_stage.device_id IS '策略生效或删除的设备标识';
COMMENT ON COLUMN telemetry_strategy_snapshot_stage.values_json IS '规范化最终有效策略，空对象表示删除本地策略';
COMMENT ON COLUMN telemetry_strategy_snapshot_stage.content_hash IS '单设备最终有效策略的 SHA-256';
COMMENT ON COLUMN telemetry_strategy_snapshot_stage.staged_at IS '本条目最后暂存时间';

COMMENT ON TABLE telemetry_strategy_runtime_revision IS 'Telemetry 已原子应用的产品策略运行修订水位';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.product_id IS '设备产品标识';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.strategy_type IS '已应用的稳定策略类型';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.runtime_revision IS '已应用的单调运行修订号';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.snapshot_id IS '已应用的服务端不可变快照标识';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.item_count IS '已应用增量快照的设备条目数';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.content_hash IS '已验证的完整增量快照 SHA-256';
COMMENT ON COLUMN telemetry_strategy_runtime_revision.applied_at IS '本修订完成原子切换时间';

COMMENT ON TABLE telemetry_data_quality_policy IS 'Telemetry 评估遥测时使用的单设备数据质量策略持久镜像';
COMMENT ON COLUMN telemetry_data_quality_policy.device_id IS '设备标识';
COMMENT ON COLUMN telemetry_data_quality_policy.product_id IS '设备所属产品标识';
COMMENT ON COLUMN telemetry_data_quality_policy.runtime_revision IS '写入本策略的运行修订号';
COMMENT ON COLUMN telemetry_data_quality_policy.snapshot_id IS '写入本策略的不可变快照标识';
COMMENT ON COLUMN telemetry_data_quality_policy.values_json IS '通过类型校验的最终有效数据质量策略';
COMMENT ON COLUMN telemetry_data_quality_policy.content_hash IS '单设备策略内容 SHA-256';
COMMENT ON COLUMN telemetry_data_quality_policy.updated_at IS '本地镜像最后切换时间';

COMMENT ON TABLE telemetry_strategy_event_inbox IS 'Telemetry 已成功处理的策略运行事件幂等凭据';
COMMENT ON COLUMN telemetry_strategy_event_inbox.event_id IS '消息总线 UUIDv7 事件标识';
COMMENT ON COLUMN telemetry_strategy_event_inbox.snapshot_id IS '事件指向的不可变策略运行快照标识';
COMMENT ON COLUMN telemetry_strategy_event_inbox.received_at IS '事件完成幂等处理时间';
