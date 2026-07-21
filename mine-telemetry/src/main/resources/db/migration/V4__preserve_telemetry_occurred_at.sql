-- 保留设备原始发生时间，time 继续作为经过时间质量判定的时序分区与排序时间。
ALTER TABLE telemetry_numeric
    ADD COLUMN occurred_at TIMESTAMPTZ;

UPDATE telemetry_numeric
SET occurred_at = time
WHERE occurred_at IS NULL;

ALTER TABLE telemetry_numeric
    ALTER COLUMN occurred_at SET NOT NULL;

COMMENT ON COLUMN telemetry_numeric.time IS '平台时间质量策略判定后的有效排序时间（UTC）';
COMMENT ON COLUMN telemetry_numeric.occurred_at IS '设备声明的原始数据发生时间（UTC）';
