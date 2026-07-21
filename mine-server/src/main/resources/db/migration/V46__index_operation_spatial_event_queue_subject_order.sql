CREATE INDEX idx_mine_operation_queue_vehicle_order
    ON mine_operation_spatial_event_queue(
        vehicle_id, occurred_at, ingest_sequence, event_id
    ) WHERE vehicle_id IS NOT NULL;

CREATE INDEX idx_mine_operation_queue_unresolved_tag_order
    ON mine_operation_spatial_event_queue(
        location_tag_id, occurred_at, ingest_sequence, event_id
    ) WHERE vehicle_id IS NULL;

COMMENT ON INDEX idx_mine_operation_queue_vehicle_order
    IS '加速按车辆计算空间事件水位、前序阻塞与最终处理前沿';
COMMENT ON INDEX idx_mine_operation_queue_unresolved_tag_order
    IS '加速按未解析定位标签计算空间事件水位、前序阻塞与最终处理前沿';
