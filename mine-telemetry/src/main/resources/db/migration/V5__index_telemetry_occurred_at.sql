-- 历史查询按设备、属性和业务发生时间过滤排序，单独建立覆盖其访问路径的索引。
CREATE INDEX idx_tn_device_property_occurred_at
    ON telemetry_numeric(device_id, property_id, occurred_at DESC);
