ALTER TABLE spatial_event_outbox
    ADD COLUMN lease_token UUID;

UPDATE spatial_event_outbox
SET status = 'FAILED',
    next_attempt_at = CURRENT_TIMESTAMP,
    locked_by = NULL,
    locked_at = NULL,
    lease_expires_at = NULL,
    last_error = COALESCE(last_error, 'LEASE_TOKEN_MIGRATION_RECOVERY')
WHERE status = 'PUBLISHING';

ALTER TABLE spatial_event_outbox
    DROP CONSTRAINT ck_spatial_event_outbox_lease;

ALTER TABLE spatial_event_outbox
    ADD CONSTRAINT ck_spatial_event_outbox_lease CHECK (
        (status = 'PUBLISHING' AND lease_token IS NOT NULL
            AND locked_by IS NOT NULL AND btrim(locked_by) <> ''
            AND locked_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > locked_at)
        OR (status <> 'PUBLISHING' AND lease_token IS NULL
            AND locked_by IS NULL AND locked_at IS NULL AND lease_expires_at IS NULL)
    );

COMMENT ON COLUMN spatial_event_outbox.lease_token IS
    '每次发布认领随机生成且防止旧执行越权完成新租约的令牌';

ALTER TABLE spatial_evaluation_queue
    ADD COLUMN allowed_lateness_millis BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN idle_flush_millis BIGINT NOT NULL DEFAULT 60000,
    ADD CONSTRAINT ck_spatial_evaluation_queue_policy CHECK (
        allowed_lateness_millis BETWEEN 0 AND 300000
        AND idle_flush_millis BETWEEN 1 AND 3600000
        AND idle_flush_millis > allowed_lateness_millis
    );

COMMENT ON COLUMN spatial_evaluation_queue.allowed_lateness_millis IS
    '入队时精确运行快照内所有生效区域的最大允许迟到毫秒数';
COMMENT ON COLUMN spatial_evaluation_queue.idle_flush_millis IS
    '入队时精确运行快照内所有生效区域的最大空闲刷新毫秒数';

CREATE TABLE spatial_runtime_snapshot (
    organization_id     UUID         NOT NULL,
    site_id             UUID         NOT NULL,
    runtime_revision    BIGINT       NOT NULL,
    snapshot_id         UUID         NOT NULL,
    zone_item_count     INTEGER      NOT NULL,
    zone_content_hash   VARCHAR(71)  NOT NULL,
    source_created_at   TIMESTAMPTZ  NOT NULL,
    captured_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_runtime_snapshot PRIMARY KEY (site_id, runtime_revision),
    CONSTRAINT uk_spatial_runtime_snapshot_scope
        UNIQUE (organization_id, site_id, runtime_revision),
    CONSTRAINT uk_spatial_runtime_snapshot_id UNIQUE (site_id, snapshot_id),
    CONSTRAINT ck_spatial_runtime_snapshot_values CHECK (
        runtime_revision > 0 AND zone_item_count > 0
        AND zone_content_hash ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE spatial_runtime_snapshot_item (
    organization_id     UUID          NOT NULL,
    site_id             UUID          NOT NULL,
    runtime_revision    BIGINT        NOT NULL,
    item_key            VARCHAR(96)   NOT NULL,
    item_type           VARCHAR(32)   NOT NULL,
    content_json        JSONB         NOT NULL,
    content_hash        VARCHAR(71)   NOT NULL,
    CONSTRAINT pk_spatial_runtime_snapshot_item
        PRIMARY KEY (site_id, runtime_revision, item_key),
    CONSTRAINT fk_spatial_runtime_snapshot_item_snapshot
        FOREIGN KEY (organization_id, site_id, runtime_revision)
        REFERENCES spatial_runtime_snapshot (
            organization_id, site_id, runtime_revision
        ),
    CONSTRAINT ck_spatial_runtime_snapshot_item_type
        CHECK (item_type IN ('WAYPOINT_VERSION', 'GEOFENCE_VERSION')),
    CONSTRAINT ck_spatial_runtime_snapshot_item_key CHECK (
        item_key ~ '^(WAYPOINT_VERSION|GEOFENCE_VERSION):[0-9a-f-]{36}$'
    ),
    CONSTRAINT ck_spatial_runtime_snapshot_item_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_spatial_runtime_snapshot_item_size
        CHECK (pg_column_size(content_json) <= 1048576)
);

CREATE INDEX idx_spatial_runtime_snapshot_item_revision
    ON spatial_runtime_snapshot_item(site_id, runtime_revision, item_type, item_key);

COMMENT ON TABLE spatial_runtime_snapshot IS
    '位置事实入队时固化的精确空间运行快照头，避免新配置判定旧事实';
COMMENT ON COLUMN spatial_runtime_snapshot.organization_id IS '客户组织标识';
COMMENT ON COLUMN spatial_runtime_snapshot.site_id IS '矿区标识';
COMMENT ON COLUMN spatial_runtime_snapshot.runtime_revision IS '定位运行快照修订号';
COMMENT ON COLUMN spatial_runtime_snapshot.snapshot_id IS '定位运行快照标识';
COMMENT ON COLUMN spatial_runtime_snapshot.zone_item_count IS '快照内空间区域版本条目数量';
COMMENT ON COLUMN spatial_runtime_snapshot.zone_content_hash IS '有序区域版本键与摘要的聚合摘要';
COMMENT ON COLUMN spatial_runtime_snapshot.source_created_at IS '控制面生成原始定位快照的时间';
COMMENT ON COLUMN spatial_runtime_snapshot.captured_at IS 'Telemetry 首次固化该空间修订的时间';

COMMENT ON TABLE spatial_runtime_snapshot_item IS
    '精确空间运行快照内不可变的途径点和电子围栏发布版本';
COMMENT ON COLUMN spatial_runtime_snapshot_item.organization_id IS '客户组织标识';
COMMENT ON COLUMN spatial_runtime_snapshot_item.site_id IS '矿区标识';
COMMENT ON COLUMN spatial_runtime_snapshot_item.runtime_revision IS '定位运行快照修订号';
COMMENT ON COLUMN spatial_runtime_snapshot_item.item_key IS '带类型前缀的稳定快照条目键';
COMMENT ON COLUMN spatial_runtime_snapshot_item.item_type IS '途径点版本或电子围栏版本类型';
COMMENT ON COLUMN spatial_runtime_snapshot_item.content_json IS '已校验的规范化非敏感区域运行内容';
COMMENT ON COLUMN spatial_runtime_snapshot_item.content_hash IS '区域运行内容的 SHA-256 摘要';
