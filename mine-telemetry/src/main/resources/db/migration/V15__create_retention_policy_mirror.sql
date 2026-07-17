-- 数据保留策略使用独立修订水位和带类型化生成列的单设备最后正确镜像。
ALTER TABLE telemetry_strategy_runtime_revision
    DROP CONSTRAINT ck_telemetry_strategy_runtime_revision_type,
    ADD CONSTRAINT ck_telemetry_strategy_runtime_revision_type
        CHECK (strategy_type IN ('DATA_QUALITY', 'ALARM', 'RETENTION'));

CREATE TABLE telemetry_retention_policy (
    device_id                 UUID        NOT NULL,
    product_id                UUID        NOT NULL,
    runtime_revision          BIGINT      NOT NULL,
    snapshot_id               UUID        NOT NULL,
    values_json               JSONB       NOT NULL,
    content_hash              VARCHAR(71) NOT NULL,
    enabled                   BOOLEAN GENERATED ALWAYS AS (
        ((values_json -> 'policy' ->> 'enabled')::BOOLEAN)
    ) STORED,
    retention_class           VARCHAR(16) GENERATED ALWAYS AS (
        (values_json -> 'policy' ->> 'retentionClass')
    ) STORED,
    raw_telemetry_days        INTEGER GENERATED ALWAYS AS (
        ((values_json -> 'policy' ->> 'rawTelemetryDays')::INTEGER)
    ) STORED,
    quality_evidence_days     INTEGER GENERATED ALWAYS AS (
        ((values_json -> 'policy' ->> 'qualityEvidenceDays')::INTEGER)
    ) STORED,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_retention_policy PRIMARY KEY (device_id),
    CONSTRAINT ck_telemetry_retention_policy_revision CHECK (runtime_revision > 0),
    CONSTRAINT ck_telemetry_retention_policy_values CHECK (
        jsonb_typeof(values_json) = 'object'
        AND values_json <> '{}'::JSONB
        AND jsonb_typeof(values_json -> 'policy') = 'object'
    ),
    CONSTRAINT ck_telemetry_retention_policy_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_telemetry_retention_policy_class CHECK (
        retention_class IN ('SHORT', 'STANDARD', 'LONG', 'ARCHIVE')
    ),
    CONSTRAINT ck_telemetry_retention_policy_days CHECK (
        raw_telemetry_days BETWEEN 7 AND 3650
        AND quality_evidence_days BETWEEN raw_telemetry_days AND 3650
        AND (
            (retention_class = 'SHORT' AND raw_telemetry_days BETWEEN 7 AND 30)
            OR (retention_class = 'STANDARD' AND raw_telemetry_days BETWEEN 31 AND 180)
            OR (retention_class = 'LONG' AND raw_telemetry_days BETWEEN 181 AND 730)
            OR (retention_class = 'ARCHIVE' AND raw_telemetry_days BETWEEN 731 AND 3650)
        )
    )
);

CREATE INDEX idx_telemetry_retention_policy_product
    ON telemetry_retention_policy (product_id, device_id);

CREATE INDEX idx_telemetry_retention_policy_enabled
    ON telemetry_retention_policy (raw_telemetry_days, quality_evidence_days, device_id)
    WHERE enabled;

COMMENT ON TABLE telemetry_retention_policy IS 'Telemetry 清理过期数据时使用的单设备数据保留策略最后正确镜像';
COMMENT ON COLUMN telemetry_retention_policy.device_id IS '设备标识';
COMMENT ON COLUMN telemetry_retention_policy.product_id IS '设备所属产品标识';
COMMENT ON COLUMN telemetry_retention_policy.runtime_revision IS '写入本策略的数据保留运行修订号';
COMMENT ON COLUMN telemetry_retention_policy.snapshot_id IS '写入本策略的不可变数据保留快照标识';
COMMENT ON COLUMN telemetry_retention_policy.values_json IS '通过共享类型注册表校验的最终有效数据保留策略';
COMMENT ON COLUMN telemetry_retention_policy.content_hash IS '单设备数据保留策略内容 SHA-256';
COMMENT ON COLUMN telemetry_retention_policy.enabled IS '从策略对象生成的安全执行启用状态';
COMMENT ON COLUMN telemetry_retention_policy.retention_class IS '从策略对象生成的稳定数据保留等级';
COMMENT ON COLUMN telemetry_retention_policy.raw_telemetry_days IS '从策略对象生成的原始数值遥测最短保留天数';
COMMENT ON COLUMN telemetry_retention_policy.quality_evidence_days IS '从策略对象生成的质量证据最短保留天数';
COMMENT ON COLUMN telemetry_retention_policy.updated_at IS '本地数据保留策略镜像最后切换时间';
