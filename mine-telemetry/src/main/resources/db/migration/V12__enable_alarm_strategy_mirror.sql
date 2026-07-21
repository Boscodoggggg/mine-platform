-- 告警策略使用独立修订水位和单设备最后正确镜像，不与数据质量策略互相覆盖。
ALTER TABLE telemetry_strategy_runtime_revision
    DROP CONSTRAINT ck_telemetry_strategy_runtime_revision_type,
    ADD CONSTRAINT ck_telemetry_strategy_runtime_revision_type
        CHECK (strategy_type IN ('DATA_QUALITY', 'ALARM'));

CREATE TABLE telemetry_alarm_policy (
    device_id         UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    runtime_revision  BIGINT      NOT NULL,
    snapshot_id       UUID        NOT NULL,
    values_json       JSONB       NOT NULL,
    content_hash      VARCHAR(71) NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_alarm_policy PRIMARY KEY (device_id),
    CONSTRAINT ck_telemetry_alarm_policy_revision CHECK (runtime_revision > 0),
    CONSTRAINT ck_telemetry_alarm_policy_values CHECK (
        jsonb_typeof(values_json) = 'object' AND values_json <> '{}'::JSONB
    ),
    CONSTRAINT ck_telemetry_alarm_policy_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_telemetry_alarm_policy_product
    ON telemetry_alarm_policy (product_id, device_id);

COMMENT ON TABLE telemetry_alarm_policy IS 'Telemetry 评估遥测时使用的单设备告警策略最后正确镜像';
COMMENT ON COLUMN telemetry_alarm_policy.device_id IS '设备标识';
COMMENT ON COLUMN telemetry_alarm_policy.product_id IS '设备所属产品标识';
COMMENT ON COLUMN telemetry_alarm_policy.runtime_revision IS '写入本策略的告警运行修订号';
COMMENT ON COLUMN telemetry_alarm_policy.snapshot_id IS '写入本策略的不可变告警快照标识';
COMMENT ON COLUMN telemetry_alarm_policy.values_json IS '通过共享类型注册表校验的最终有效告警策略';
COMMENT ON COLUMN telemetry_alarm_policy.content_hash IS '单设备告警策略内容 SHA-256';
COMMENT ON COLUMN telemetry_alarm_policy.updated_at IS '本地告警策略镜像最后切换时间';
