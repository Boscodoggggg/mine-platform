-- 聚合策略使用独立修订水位和结构化单设备最后正确镜像。
ALTER TABLE telemetry_strategy_runtime_revision
    DROP CONSTRAINT ck_telemetry_strategy_runtime_revision_type,
    ADD CONSTRAINT ck_telemetry_strategy_runtime_revision_type
        CHECK (strategy_type IN ('DATA_QUALITY', 'ALARM', 'RETENTION', 'AGGREGATION'));

CREATE OR REPLACE FUNCTION validate_telemetry_aggregation_policy(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    policy JSONB;
    rule JSONB;
    correction_window NUMERIC;
    bucket_width NUMERIC;
    property_identifier TEXT;
    stable_rule_key TEXT;
    seen_rule_keys TEXT[] := ARRAY[]::TEXT[];
    maximum_bucket INTEGER := 0;
BEGIN
    IF jsonb_typeof(candidate) <> 'object'
       OR NOT candidate ? 'policy'
       OR candidate - 'policy' <> '{}'::JSONB THEN
        RETURN FALSE;
    END IF;
    policy := candidate -> 'policy';
    IF jsonb_typeof(policy) <> 'object'
       OR NOT policy ?& ARRAY['enabled', 'correctionWindowSeconds', 'rules']
       OR policy - ARRAY['enabled', 'correctionWindowSeconds', 'rules'] <> '{}'::JSONB
       OR jsonb_typeof(policy -> 'enabled') <> 'boolean'
       OR jsonb_typeof(policy -> 'correctionWindowSeconds') <> 'number'
       OR jsonb_typeof(policy -> 'rules') <> 'array'
       OR jsonb_array_length(policy -> 'rules') NOT BETWEEN 1 AND 100 THEN
        RETURN FALSE;
    END IF;

    correction_window := (policy ->> 'correctionWindowSeconds')::NUMERIC;
    IF correction_window <> trunc(correction_window)
       OR correction_window NOT BETWEEN 300 AND 604800 THEN
        RETURN FALSE;
    END IF;

    FOR rule IN SELECT value FROM jsonb_array_elements(policy -> 'rules') LOOP
        IF jsonb_typeof(rule) <> 'object'
           OR NOT rule ?& ARRAY['propertyId', 'bucketSeconds']
           OR rule - ARRAY['propertyId', 'bucketSeconds'] <> '{}'::JSONB
           OR jsonb_typeof(rule -> 'propertyId') <> 'string'
           OR jsonb_typeof(rule -> 'bucketSeconds') <> 'number' THEN
            RETURN FALSE;
        END IF;
        property_identifier := rule ->> 'propertyId';
        bucket_width := (rule ->> 'bucketSeconds')::NUMERIC;
        IF property_identifier !~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'
           OR bucket_width <> trunc(bucket_width)
           OR bucket_width::INTEGER <> ALL (ARRAY[60, 300, 900, 3600, 21600, 86400]) THEN
            RETURN FALSE;
        END IF;
        stable_rule_key := property_identifier || ':' || bucket_width::INTEGER;
        IF stable_rule_key = ANY (seen_rule_keys) THEN
            RETURN FALSE;
        END IF;
        seen_rule_keys := array_append(seen_rule_keys, stable_rule_key);
        maximum_bucket := greatest(maximum_bucket, bucket_width::INTEGER);
    END LOOP;
    RETURN correction_window >= maximum_bucket * 2;
EXCEPTION
    WHEN OTHERS THEN
        RETURN FALSE;
END;
$$;

CREATE TABLE telemetry_aggregation_policy (
    device_id                 UUID        NOT NULL,
    product_id                UUID        NOT NULL,
    runtime_revision          BIGINT      NOT NULL,
    snapshot_id               UUID        NOT NULL,
    values_json               JSONB       NOT NULL,
    content_hash              VARCHAR(71) NOT NULL,
    enabled                   BOOLEAN GENERATED ALWAYS AS (
        ((values_json -> 'policy' ->> 'enabled')::BOOLEAN)
    ) STORED,
    correction_window_seconds INTEGER GENERATED ALWAYS AS (
        ((values_json -> 'policy' ->> 'correctionWindowSeconds')::INTEGER)
    ) STORED,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_aggregation_policy PRIMARY KEY (device_id),
    CONSTRAINT ck_telemetry_aggregation_policy_revision CHECK (runtime_revision > 0),
    CONSTRAINT ck_telemetry_aggregation_policy_values
        CHECK (validate_telemetry_aggregation_policy(values_json)),
    CONSTRAINT ck_telemetry_aggregation_policy_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_telemetry_aggregation_policy_enabled CHECK (enabled IS NOT NULL),
    CONSTRAINT ck_telemetry_aggregation_policy_correction CHECK (
        correction_window_seconds BETWEEN 300 AND 604800
    )
);

CREATE INDEX idx_telemetry_aggregation_policy_product
    ON telemetry_aggregation_policy (product_id, device_id);

CREATE INDEX idx_telemetry_aggregation_policy_enabled
    ON telemetry_aggregation_policy (correction_window_seconds, device_id)
    WHERE enabled;

COMMENT ON FUNCTION validate_telemetry_aggregation_policy(JSONB)
    IS '校验聚合策略 JSON 只包含固定属性、桶宽和有界迟到修正窗口';
COMMENT ON TABLE telemetry_aggregation_policy
    IS 'Telemetry 数值聚合任务使用的单设备聚合策略最后正确镜像';
COMMENT ON COLUMN telemetry_aggregation_policy.device_id IS '设备标识';
COMMENT ON COLUMN telemetry_aggregation_policy.product_id IS '设备所属产品标识';
COMMENT ON COLUMN telemetry_aggregation_policy.runtime_revision IS '写入本策略的聚合运行修订号';
COMMENT ON COLUMN telemetry_aggregation_policy.snapshot_id IS '写入本策略的不可变聚合快照标识';
COMMENT ON COLUMN telemetry_aggregation_policy.values_json IS '通过共享类型注册表校验的最终有效聚合策略';
COMMENT ON COLUMN telemetry_aggregation_policy.content_hash IS '单设备聚合策略内容 SHA-256';
COMMENT ON COLUMN telemetry_aggregation_policy.enabled IS '从策略对象生成的安全执行启用状态';
COMMENT ON COLUMN telemetry_aggregation_policy.correction_window_seconds IS '从策略对象生成的迟到数据修正窗口秒数';
COMMENT ON COLUMN telemetry_aggregation_policy.updated_at IS '本地聚合策略镜像最后切换时间';
