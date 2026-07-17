-- 为显式重算执行补充不可变请求身份、范围和单策略证据，确保稳定执行标识可安全重放。
ALTER TABLE telemetry_aggregation_execution
    ADD COLUMN execution_kind VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    ADD COLUMN recompute_request_id UUID,
    ADD COLUMN target_device_id UUID,
    ADD COLUMN target_property_id VARCHAR(128),
    ADD COLUMN target_bucket_seconds INTEGER,
    ADD COLUMN requested_from TIMESTAMPTZ,
    ADD COLUMN requested_to TIMESTAMPTZ,
    ADD COLUMN runtime_revision BIGINT,
    ADD COLUMN snapshot_id UUID,
    ADD COLUMN policy_content_hash VARCHAR(71);

ALTER TABLE telemetry_aggregation_execution
    ADD CONSTRAINT ck_telemetry_aggregation_execution_kind
        CHECK (execution_kind IN ('SCHEDULED', 'RECOMPUTE')),
    ADD CONSTRAINT ck_telemetry_aggregation_execution_recompute CHECK (
        (execution_kind = 'SCHEDULED'
            AND recompute_request_id IS NULL
            AND target_device_id IS NULL
            AND target_property_id IS NULL
            AND target_bucket_seconds IS NULL
            AND requested_from IS NULL
            AND requested_to IS NULL
            AND runtime_revision IS NULL
            AND snapshot_id IS NULL
            AND policy_content_hash IS NULL)
        OR
        (execution_kind = 'RECOMPUTE'
            AND recompute_request_id IS NOT NULL
            AND target_device_id IS NOT NULL
            AND target_property_id ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'
            AND target_bucket_seconds IN (60, 300, 900, 3600, 21600, 86400)
            AND requested_from IS NOT NULL
            AND requested_to > requested_from
            AND mod(extract(microseconds FROM requested_from)::BIGINT, 1000000) = 0
            AND mod(extract(microseconds FROM requested_to)::BIGINT, 1000000) = 0
            AND mod(extract(epoch FROM requested_from)::BIGINT, target_bucket_seconds) = 0
            AND mod(extract(epoch FROM requested_to)::BIGINT, target_bucket_seconds) = 0
            AND requested_to <= requested_from
                + target_bucket_seconds * 10000 * INTERVAL '1 second'
            AND (
                (policy_count = 0 AND rule_count = 0
                    AND runtime_revision IS NULL
                    AND snapshot_id IS NULL
                    AND policy_content_hash IS NULL
                    AND status <> 'SUCCEEDED')
                OR
                (policy_count = 1 AND rule_count = 1
                    AND runtime_revision > 0
                    AND snapshot_id IS NOT NULL
                    AND policy_content_hash ~ '^sha256:[0-9a-f]{64}$')
            ))
    );

CREATE UNIQUE INDEX uq_telemetry_aggregation_execution_recompute_request
    ON telemetry_aggregation_execution (recompute_request_id)
    WHERE recompute_request_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_telemetry_aggregation_execution()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '聚合执行事实禁止删除';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION '聚合终态事实禁止修改';
    END IF;
    IF NEW.execution_id <> OLD.execution_id
            OR NEW.instance_id <> OLD.instance_id
            OR NEW.policy_count <> OLD.policy_count
            OR NEW.rule_count <> OLD.rule_count
            OR NEW.source_watermark <> OLD.source_watermark
            OR NEW.started_at <> OLD.started_at
            OR NEW.execution_kind <> OLD.execution_kind
            OR NEW.recompute_request_id IS DISTINCT FROM OLD.recompute_request_id
            OR NEW.target_device_id IS DISTINCT FROM OLD.target_device_id
            OR NEW.target_property_id IS DISTINCT FROM OLD.target_property_id
            OR NEW.target_bucket_seconds IS DISTINCT FROM OLD.target_bucket_seconds
            OR NEW.requested_from IS DISTINCT FROM OLD.requested_from
            OR NEW.requested_to IS DISTINCT FROM OLD.requested_to
            OR NEW.runtime_revision IS DISTINCT FROM OLD.runtime_revision
            OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
            OR NEW.policy_content_hash IS DISTINCT FROM OLD.policy_content_hash THEN
        RAISE EXCEPTION '聚合执行身份字段禁止修改';
    END IF;
    IF NEW.bucket_count < OLD.bucket_count
            OR (OLD.rollup_watermark IS NOT NULL
                AND (NEW.rollup_watermark IS NULL
                     OR NEW.rollup_watermark < OLD.rollup_watermark))
            OR NEW.late_rows_ignored < OLD.late_rows_ignored THEN
        RAISE EXCEPTION '聚合执行进度禁止回退';
    END IF;
    IF NEW.status = 'RUNNING' THEN
        RETURN NEW;
    END IF;
    IF NEW.status NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED') THEN
        RAISE EXCEPTION '聚合运行事实只能收敛到终态';
    END IF;
    RETURN NEW;
END
$function$;

COMMENT ON COLUMN telemetry_aggregation_execution.execution_kind IS '计划聚合或显式重算执行类型';
COMMENT ON COLUMN telemetry_aggregation_execution.recompute_request_id IS '显式重算对应的控制面请求标识';
COMMENT ON COLUMN telemetry_aggregation_execution.target_device_id IS '显式重算目标设备标识';
COMMENT ON COLUMN telemetry_aggregation_execution.target_property_id IS '显式重算目标数值属性标识';
COMMENT ON COLUMN telemetry_aggregation_execution.target_bucket_seconds IS '显式重算固定桶宽秒数';
COMMENT ON COLUMN telemetry_aggregation_execution.requested_from IS '显式重算左闭开始时间';
COMMENT ON COLUMN telemetry_aggregation_execution.requested_to IS '显式重算右开结束时间';
COMMENT ON COLUMN telemetry_aggregation_execution.runtime_revision IS '显式重算使用的聚合策略运行修订号';
COMMENT ON COLUMN telemetry_aggregation_execution.snapshot_id IS '显式重算使用的不可变聚合策略快照标识';
COMMENT ON COLUMN telemetry_aggregation_execution.policy_content_hash IS '显式重算使用的单设备聚合策略内容 SHA-256';

COMMENT ON FUNCTION protect_telemetry_aggregation_execution()
    IS '允许聚合进度单向增加并阻止删除或篡改包括显式重算身份在内的执行事实';
