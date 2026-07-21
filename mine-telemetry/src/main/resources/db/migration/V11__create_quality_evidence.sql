-- 质量违规不丢弃原始属性，结构化证据与遥测历史在同一事务中提交。
CREATE TABLE telemetry_quality_evidence (
    event_id                 UUID         NOT NULL,
    device_id                UUID         NOT NULL,
    property_id              VARCHAR(128) NOT NULL,
    rule_code                VARCHAR(32)  NOT NULL,
    result_quality           SMALLINT     NOT NULL,
    observed_value           NUMERIC,
    threshold_value          NUMERIC,
    policy_runtime_revision  BIGINT       NOT NULL,
    policy_snapshot_id       UUID         NOT NULL,
    policy_content_hash      VARCHAR(71)  NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_quality_evidence
        PRIMARY KEY (event_id, property_id, rule_code),
    CONSTRAINT ck_telemetry_quality_evidence_property CHECK (btrim(property_id) <> ''),
    CONSTRAINT ck_telemetry_quality_evidence_rule CHECK (rule_code IN (
        'FUTURE_TIMESTAMP', 'STALE_TIMESTAMP', 'REQUIRED_MISSING',
        'BELOW_MINIMUM', 'ABOVE_MAXIMUM'
    )),
    CONSTRAINT ck_telemetry_quality_evidence_result CHECK (result_quality BETWEEN 1 AND 3),
    CONSTRAINT ck_telemetry_quality_evidence_values CHECK (
        (rule_code = 'REQUIRED_MISSING'
            AND observed_value IS NULL AND threshold_value IS NULL)
        OR
        (rule_code <> 'REQUIRED_MISSING'
            AND observed_value IS NOT NULL AND threshold_value IS NOT NULL)
    ),
    CONSTRAINT ck_telemetry_quality_evidence_revision CHECK (policy_runtime_revision > 0),
    CONSTRAINT ck_telemetry_quality_evidence_hash
        CHECK (policy_content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_telemetry_quality_evidence_device_time
    ON telemetry_quality_evidence (device_id, created_at DESC);

CREATE INDEX idx_telemetry_quality_evidence_created_brin
    ON telemetry_quality_evidence USING BRIN (created_at);

COMMENT ON TABLE telemetry_quality_evidence IS '与遥测历史同事务提交的属性级数据质量违规证据';
COMMENT ON COLUMN telemetry_quality_evidence.event_id IS '原始遥测 UUIDv7 事件标识';
COMMENT ON COLUMN telemetry_quality_evidence.device_id IS '产生质量违规的设备标识';
COMMENT ON COLUMN telemetry_quality_evidence.property_id IS '产生违规或缺失的物模型属性标识';
COMMENT ON COLUMN telemetry_quality_evidence.rule_code IS '稳定且低基数的数据质量规则编码';
COMMENT ON COLUMN telemetry_quality_evidence.result_quality IS '规则产生的数据质量编码，1 不确定、2 无效、3 时间不可信';
COMMENT ON COLUMN telemetry_quality_evidence.observed_value IS '观测数值或设备时间相对平台时间的偏移秒数';
COMMENT ON COLUMN telemetry_quality_evidence.threshold_value IS '命中规则时使用的策略阈值';
COMMENT ON COLUMN telemetry_quality_evidence.policy_runtime_revision IS '执行本规则的数据质量策略运行修订号';
COMMENT ON COLUMN telemetry_quality_evidence.policy_snapshot_id IS '执行本规则的不可变策略运行快照标识';
COMMENT ON COLUMN telemetry_quality_evidence.policy_content_hash IS '执行本规则的单设备有效策略 SHA-256';
COMMENT ON COLUMN telemetry_quality_evidence.created_at IS '质量证据完成持久化的时间';
