-- 首页按接收时间统计最近消息和小时趋势，避免遥测消息增长后反复全表扫描。
CREATE INDEX idx_ti_received_at
    ON telemetry_inbox (received_at DESC);

COMMENT ON INDEX idx_ti_received_at IS '支持按平台接收时间查询遥测消息和首页趋势';
