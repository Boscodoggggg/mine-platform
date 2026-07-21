-- 聚合执行事实先于版本化结果创建，以便每个聚合版本都具有不可伪造的执行来源。
CREATE TABLE telemetry_aggregation_execution (
    execution_id       UUID         NOT NULL,
    instance_id        VARCHAR(128) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    policy_count       INTEGER      NOT NULL,
    rule_count         INTEGER      NOT NULL,
    source_watermark   BIGINT       NOT NULL,
    bucket_count       INTEGER      NOT NULL DEFAULT 0,
    rollup_watermark   BIGINT,
    late_rows_ignored  BIGINT       NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMPTZ,
    last_error         VARCHAR(2000),
    CONSTRAINT pk_telemetry_aggregation_execution PRIMARY KEY (execution_id),
    CONSTRAINT uq_telemetry_aggregation_execution_source
        UNIQUE (execution_id, source_watermark),
    CONSTRAINT ck_telemetry_aggregation_execution_instance CHECK (btrim(instance_id) <> ''),
    CONSTRAINT ck_telemetry_aggregation_execution_counts CHECK (
        policy_count >= 0
        AND rule_count >= 0
        AND source_watermark >= 0
        AND bucket_count >= 0
        AND (rollup_watermark IS NULL OR rollup_watermark > 0)
        AND late_rows_ignored >= 0
    ),
    CONSTRAINT ck_telemetry_aggregation_execution_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')
    ),
    CONSTRAINT ck_telemetry_aggregation_execution_completion CHECK (
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

CREATE INDEX idx_telemetry_aggregation_execution_started
    ON telemetry_aggregation_execution (started_at DESC, execution_id DESC);

CREATE INDEX idx_telemetry_aggregation_execution_running
    ON telemetry_aggregation_execution (started_at, execution_id)
    WHERE status = 'RUNNING';

CREATE TABLE telemetry_numeric_rollup (
    rollup_revision      BIGINT GENERATED ALWAYS AS IDENTITY,
    device_id            UUID             NOT NULL,
    product_key          TEXT             NOT NULL,
    property_id          TEXT             NOT NULL,
    bucket_seconds       INTEGER          NOT NULL,
    bucket_start         TIMESTAMPTZ      NOT NULL,
    bucket_end           TIMESTAMPTZ      NOT NULL,
    average_value        DOUBLE PRECISION NOT NULL,
    minimum_value        DOUBLE PRECISION NOT NULL,
    maximum_value        DOUBLE PRECISION NOT NULL,
    sample_count         BIGINT           NOT NULL,
    worst_quality        SMALLINT         NOT NULL,
    latest_received_at   TIMESTAMPTZ      NOT NULL,
    source_watermark     BIGINT           NOT NULL,
    runtime_revision     BIGINT           NOT NULL,
    snapshot_id          UUID             NOT NULL,
    policy_content_hash  VARCHAR(71)      NOT NULL,
    execution_id         UUID             NOT NULL,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_numeric_rollup
        PRIMARY KEY (bucket_start, rollup_revision),
    CONSTRAINT fk_telemetry_numeric_rollup_execution
        FOREIGN KEY (execution_id, source_watermark)
        REFERENCES telemetry_aggregation_execution (execution_id, source_watermark),
    CONSTRAINT ck_telemetry_numeric_rollup_product
        CHECK (char_length(product_key) BETWEEN 1 AND 64),
    CONSTRAINT ck_telemetry_numeric_rollup_property
        CHECK (property_id ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_telemetry_numeric_rollup_bucket CHECK (
        bucket_seconds IN (60, 300, 900, 3600, 21600, 86400)
        AND bucket_end = bucket_start + bucket_seconds * INTERVAL '1 second'
        AND mod(extract(microseconds FROM bucket_start)::BIGINT, 1000000) = 0
        AND mod(extract(epoch FROM bucket_start)::BIGINT, bucket_seconds) = 0
    ),
    CONSTRAINT ck_telemetry_numeric_rollup_values CHECK (
        minimum_value BETWEEN '-1.7976931348623157e308'::DOUBLE PRECISION
                          AND '1.7976931348623157e308'::DOUBLE PRECISION
        AND maximum_value BETWEEN '-1.7976931348623157e308'::DOUBLE PRECISION
                          AND '1.7976931348623157e308'::DOUBLE PRECISION
        AND average_value BETWEEN minimum_value AND maximum_value
        AND sample_count > 0
        AND worst_quality BETWEEN 0 AND 3
    ),
    CONSTRAINT ck_telemetry_numeric_rollup_evidence CHECK (
        source_watermark >= 0
        AND runtime_revision > 0
        AND policy_content_hash ~ '^sha256:[0-9a-f]{64}$'
    )
);

SELECT create_hypertable('telemetry_numeric_rollup', by_range('bucket_start'));

CREATE UNIQUE INDEX uq_telemetry_numeric_rollup_execution_bucket
    ON telemetry_numeric_rollup (
        bucket_start, execution_id, device_id, property_id, bucket_seconds
    );

CREATE INDEX idx_telemetry_numeric_rollup_query
    ON telemetry_numeric_rollup (
        device_id, property_id, bucket_seconds, bucket_start DESC, rollup_revision DESC
    );

CREATE INDEX idx_telemetry_numeric_rollup_coverage
    ON telemetry_numeric_rollup (
        device_id, property_id, bucket_seconds, bucket_start, bucket_end,
        source_watermark DESC, runtime_revision DESC
    );

CREATE OR REPLACE FUNCTION protect_telemetry_numeric_rollup()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '数值聚合事实禁止删除';
    END IF;
    RAISE EXCEPTION '数值聚合事实禁止修改';
END
$function$;

CREATE TRIGGER trg_telemetry_numeric_rollup_protect
BEFORE UPDATE OR DELETE ON telemetry_numeric_rollup
FOR EACH ROW EXECUTE FUNCTION protect_telemetry_numeric_rollup();

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
            OR NEW.started_at <> OLD.started_at THEN
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

CREATE TRIGGER trg_telemetry_aggregation_execution_protect
BEFORE UPDATE OR DELETE ON telemetry_aggregation_execution
FOR EACH ROW EXECUTE FUNCTION protect_telemetry_aggregation_execution();

COMMENT ON TABLE telemetry_aggregation_execution IS 'Telemetry 数值聚合任务不可删除的单轮执行事实';
COMMENT ON COLUMN telemetry_aggregation_execution.execution_id IS '单轮数值聚合执行 UUIDv7 标识';
COMMENT ON COLUMN telemetry_aggregation_execution.instance_id IS '获得数据库单实例锁的 Telemetry 实例标识';
COMMENT ON COLUMN telemetry_aggregation_execution.status IS '运行中、成功、部分完成或失败状态';
COMMENT ON COLUMN telemetry_aggregation_execution.policy_count IS '本轮开始时启用的设备聚合策略数量';
COMMENT ON COLUMN telemetry_aggregation_execution.rule_count IS '本轮开始时启用的属性桶规则数量';
COMMENT ON COLUMN telemetry_aggregation_execution.source_watermark IS '本轮只允许读取到的最大原始遥测摄取水位';
COMMENT ON COLUMN telemetry_aggregation_execution.bucket_count IS '本轮已提交的版本化聚合桶数量';
COMMENT ON COLUMN telemetry_aggregation_execution.rollup_watermark IS '本轮已经提交的最大聚合版本修订号';
COMMENT ON COLUMN telemetry_aggregation_execution.late_rows_ignored IS '超出策略修正窗口而未参与重算的迟到原始遥测行数';
COMMENT ON COLUMN telemetry_aggregation_execution.started_at IS '数据库记录的本轮开始时间';
COMMENT ON COLUMN telemetry_aggregation_execution.completed_at IS '数据库记录的本轮终态时间';
COMMENT ON COLUMN telemetry_aggregation_execution.last_error IS '失败时脱敏并截断的最近错误摘要';

COMMENT ON TABLE telemetry_numeric_rollup IS '按源水位和策略证据追加版本的数值遥测聚合事实';
COMMENT ON COLUMN telemetry_numeric_rollup.rollup_revision IS '数据库分配的全局递增聚合版本修订号';
COMMENT ON COLUMN telemetry_numeric_rollup.device_id IS '聚合桶所属设备标识';
COMMENT ON COLUMN telemetry_numeric_rollup.product_key IS '聚合时设备所属产品稳定键';
COMMENT ON COLUMN telemetry_numeric_rollup.property_id IS '参与聚合的数值属性标识';
COMMENT ON COLUMN telemetry_numeric_rollup.bucket_seconds IS '固定白名单中的聚合桶宽秒数';
COMMENT ON COLUMN telemetry_numeric_rollup.bucket_start IS '按 UTC 对齐且左闭的聚合桶开始时间';
COMMENT ON COLUMN telemetry_numeric_rollup.bucket_end IS '右开的聚合桶结束时间';
COMMENT ON COLUMN telemetry_numeric_rollup.average_value IS '桶内有限数值样本的算术平均值';
COMMENT ON COLUMN telemetry_numeric_rollup.minimum_value IS '桶内有限数值样本最小值';
COMMENT ON COLUMN telemetry_numeric_rollup.maximum_value IS '桶内有限数值样本最大值';
COMMENT ON COLUMN telemetry_numeric_rollup.sample_count IS '桶内纳入聚合的原始数值样本数量';
COMMENT ON COLUMN telemetry_numeric_rollup.worst_quality IS '桶内样本最差质量等级序号';
COMMENT ON COLUMN telemetry_numeric_rollup.latest_received_at IS '桶内样本最晚平台接收时间';
COMMENT ON COLUMN telemetry_numeric_rollup.source_watermark IS '生成本版本时可见的最大原始遥测摄取水位';
COMMENT ON COLUMN telemetry_numeric_rollup.runtime_revision IS '生成本版本所用聚合策略运行修订号';
COMMENT ON COLUMN telemetry_numeric_rollup.snapshot_id IS '生成本版本所用不可变聚合策略快照标识';
COMMENT ON COLUMN telemetry_numeric_rollup.policy_content_hash IS '生成本版本所用单设备聚合策略内容 SHA-256';
COMMENT ON COLUMN telemetry_numeric_rollup.execution_id IS '生成本版本的数值聚合执行事实标识';
COMMENT ON COLUMN telemetry_numeric_rollup.created_at IS '数据库提交本聚合版本的时间';

COMMENT ON FUNCTION protect_telemetry_numeric_rollup() IS '阻止更新或删除已提交的版本化数值聚合事实';
COMMENT ON FUNCTION protect_telemetry_aggregation_execution() IS '允许聚合进度单向增加并阻止删除或篡改聚合终态执行事实';
