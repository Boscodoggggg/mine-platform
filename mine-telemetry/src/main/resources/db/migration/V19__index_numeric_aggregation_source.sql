-- 聚合迟到水位扫描按全局摄取顺序收敛，避免在大规模时序分区中执行无索引范围扫描。
CREATE INDEX idx_tn_aggregation_ingest_time
    ON telemetry_numeric (ingest_id, time);
