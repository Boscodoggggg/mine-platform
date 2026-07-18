-- 为固定摄取水位读取提供矿区前缀索引，避免按矿区扫描全部历史分区。
CREATE INDEX idx_location_site_ingest
    ON location_point(site_id, ingest_sequence DESC)
    WHERE site_id IS NOT NULL;

-- Redis 缺失时按矿区和标签从可靠实时 Outbox 选择当前事实。
CREATE INDEX idx_location_outbox_site_tag_ingest
    ON location_realtime_outbox (
        (payload->>'siteId'),
        (payload->>'tagId'),
        ingest_sequence DESC
    );
