CREATE INDEX idx_telemetry_outbox_event_unfinished_status
    ON telemetry_outbox_event (status)
    WHERE aggregate_type = 'ALARM_OCCURRENCE'
      AND event_type IN ('alarm.raised', 'alarm.recovered')
      AND status IN ('PENDING', 'PUBLISHING', 'FAILED', 'DEAD');

COMMENT ON INDEX idx_telemetry_outbox_event_unfinished_status
    IS '加速 Prometheus 按固定未完成状态统计告警 Outbox 事件';
