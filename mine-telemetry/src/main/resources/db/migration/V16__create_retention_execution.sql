-- 每次数据保留运行都留下可查询终态，终态事实禁止更新或删除。
CREATE TABLE telemetry_retention_execution (
    execution_id          UUID         NOT NULL,
    instance_id           VARCHAR(128) NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    policy_count          INTEGER      NOT NULL,
    raw_rows_deleted      BIGINT       NOT NULL DEFAULT 0,
    quality_rows_deleted  BIGINT       NOT NULL DEFAULT 0,
    batch_count           INTEGER      NOT NULL DEFAULT 0,
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMPTZ,
    last_error            VARCHAR(2000),
    CONSTRAINT pk_telemetry_retention_execution PRIMARY KEY (execution_id),
    CONSTRAINT ck_telemetry_retention_execution_instance CHECK (btrim(instance_id) <> ''),
    CONSTRAINT ck_telemetry_retention_execution_counts CHECK (
        policy_count >= 0
        AND raw_rows_deleted >= 0
        AND quality_rows_deleted >= 0
        AND batch_count >= 0
    ),
    CONSTRAINT ck_telemetry_retention_execution_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')
    ),
    CONSTRAINT ck_telemetry_retention_execution_completion CHECK (
        (status = 'RUNNING'
            AND completed_at IS NULL AND last_error IS NULL)
        OR
        (status IN ('SUCCEEDED', 'PARTIAL')
            AND completed_at IS NOT NULL AND completed_at >= started_at
            AND last_error IS NULL)
        OR
        (status = 'FAILED'
            AND completed_at IS NOT NULL AND completed_at >= started_at
            AND last_error IS NOT NULL AND btrim(last_error) <> '')
    )
);

CREATE INDEX idx_telemetry_retention_execution_started
    ON telemetry_retention_execution (started_at DESC, execution_id DESC);

CREATE INDEX idx_telemetry_retention_execution_running
    ON telemetry_retention_execution (started_at, execution_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_tn_retention_device_time
    ON telemetry_numeric (device_id, time, event_id, property_id);

CREATE OR REPLACE FUNCTION protect_telemetry_retention_execution()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '数据保留执行事实禁止删除';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION '数据保留终态事实禁止修改';
    END IF;
    IF NEW.execution_id <> OLD.execution_id
            OR NEW.instance_id <> OLD.instance_id
            OR NEW.policy_count <> OLD.policy_count
            OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION '数据保留执行身份字段禁止修改';
    END IF;
    IF NEW.raw_rows_deleted < OLD.raw_rows_deleted
            OR NEW.quality_rows_deleted < OLD.quality_rows_deleted
            OR NEW.batch_count < OLD.batch_count THEN
        RAISE EXCEPTION '数据保留执行进度禁止回退';
    END IF;
    IF NEW.status = 'RUNNING' THEN
        RETURN NEW;
    END IF;
    IF NEW.status NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED') THEN
        RAISE EXCEPTION '数据保留运行事实只能收敛到终态';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER trg_telemetry_retention_execution_protect
BEFORE UPDATE OR DELETE ON telemetry_retention_execution
FOR EACH ROW EXECUTE FUNCTION protect_telemetry_retention_execution();

COMMENT ON TABLE telemetry_retention_execution IS 'Telemetry 数据保留任务不可删除的单轮执行事实';
COMMENT ON COLUMN telemetry_retention_execution.execution_id IS '单轮数据保留执行 UUIDv7 标识';
COMMENT ON COLUMN telemetry_retention_execution.instance_id IS '获得数据库单实例锁的 Telemetry 实例标识';
COMMENT ON COLUMN telemetry_retention_execution.status IS '运行中、成功、部分完成或失败状态';
COMMENT ON COLUMN telemetry_retention_execution.policy_count IS '本轮开始时启用的数据保留策略数量';
COMMENT ON COLUMN telemetry_retention_execution.raw_rows_deleted IS '本轮已删除的原始数值遥测行数';
COMMENT ON COLUMN telemetry_retention_execution.quality_rows_deleted IS '本轮已删除的质量证据行数';
COMMENT ON COLUMN telemetry_retention_execution.batch_count IS '本轮实际产生删除的有界批次数';
COMMENT ON COLUMN telemetry_retention_execution.started_at IS '数据库记录的本轮开始时间';
COMMENT ON COLUMN telemetry_retention_execution.completed_at IS '数据库记录的本轮终态时间';
COMMENT ON COLUMN telemetry_retention_execution.last_error IS '失败时脱敏并截断的最近错误摘要';
COMMENT ON FUNCTION protect_telemetry_retention_execution() IS '允许运行进度单向增加并阻止删除或篡改数据保留终态执行事实';
