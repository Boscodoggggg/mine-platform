CREATE INDEX idx_spatial_event_outbox_dead
    ON spatial_event_outbox (event_id)
    WHERE status = 'DEAD';

COMMENT ON INDEX idx_spatial_event_outbox_dead IS
    '加速空间事件死亡信箱指标统计的部分索引';
