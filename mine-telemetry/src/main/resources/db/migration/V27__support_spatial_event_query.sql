-- 使用事务行锁分配序号：后续事务必须等待前序事件提交或回滚，序号才具备提交可见顺序。
CREATE TABLE spatial_event_sequence_watermark (
    singleton       BOOLEAN     NOT NULL DEFAULT TRUE,
    last_sequence   BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_event_sequence_watermark PRIMARY KEY (singleton),
    CONSTRAINT ck_spatial_event_sequence_watermark_singleton CHECK (singleton),
    CONSTRAINT ck_spatial_event_sequence_watermark_value CHECK (last_sequence >= 0)
);

INSERT INTO spatial_event_sequence_watermark(singleton, last_sequence)
VALUES (TRUE, 0);

ALTER TABLE spatial_event ADD COLUMN event_sequence BIGINT;

WITH ordered_event AS (
    SELECT event_id,
           ROW_NUMBER() OVER (ORDER BY created_at, event_id) AS event_sequence
    FROM spatial_event
)
UPDATE spatial_event event
SET event_sequence = ordered_event.event_sequence
FROM ordered_event
WHERE ordered_event.event_id = event.event_id;

UPDATE spatial_event_sequence_watermark
SET last_sequence = COALESCE((SELECT MAX(event_sequence) FROM spatial_event), 0),
    updated_at = CURRENT_TIMESTAMP
WHERE singleton = TRUE;

ALTER TABLE spatial_event ALTER COLUMN event_sequence SET NOT NULL;

CREATE FUNCTION assign_spatial_event_sequence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_sequence IS NOT NULL THEN
        RAISE EXCEPTION 'spatial event sequence must be allocated by database';
    END IF;
    UPDATE spatial_event_sequence_watermark
    SET last_sequence = last_sequence + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE singleton = TRUE
    RETURNING last_sequence INTO NEW.event_sequence;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_spatial_event_assign_sequence
BEFORE INSERT ON spatial_event
FOR EACH ROW EXECUTE FUNCTION assign_spatial_event_sequence();

ALTER TABLE spatial_event
    ADD CONSTRAINT uk_spatial_event_sequence UNIQUE (event_sequence),
    ADD CONSTRAINT ck_spatial_event_sequence CHECK (event_sequence > 0);

CREATE INDEX idx_spatial_event_scope_query
    ON spatial_event(organization_id, site_id, occurred_at DESC, event_sequence DESC);

CREATE INDEX idx_spatial_event_scope_sequence
    ON spatial_event(organization_id, site_id, event_sequence DESC);

CREATE INDEX idx_spatial_replay_request_overlap
    ON spatial_replay_request(
        organization_id, site_id, zone_type, zone_version_id, window_start, window_end
    )
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');

-- 重放请求使用固定源水位与复合游标分批执行，租约令牌阻止过期执行器覆盖新租约。
ALTER TABLE spatial_replay_request
    DROP CONSTRAINT ck_spatial_replay_request_lease,
    DROP CONSTRAINT ck_spatial_replay_request_status,
    DROP CONSTRAINT ck_spatial_replay_request_completed,
    ADD COLUMN lease_token UUID,
    ADD COLUMN source_watermark BIGINT,
    ADD COLUMN source_snapshot PG_SNAPSHOT,
    ADD COLUMN cursor_time TIMESTAMPTZ,
    ADD COLUMN cursor_ingest_sequence BIGINT,
    ADD COLUMN zone_item_type VARCHAR(32),
    ADD COLUMN zone_content JSONB,
    ADD COLUMN zone_content_hash VARCHAR(71),
    ADD COLUMN zone_runtime_revision BIGINT;

-- V27 之前尚无重放执行器，清理可能由人工留下的非最终运行状态后再启用严格约束。
UPDATE spatial_replay_request
SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP,
    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL,
    started_at = NULL, last_error = NULL
WHERE status IN ('PROCESSING', 'FAILED');

UPDATE spatial_replay_request replay
SET source_watermark = COALESCE((
        SELECT MAX(point.ingest_sequence)
        FROM location_point point
        WHERE point.organization_id = replay.organization_id
          AND point.site_id = replay.site_id
          AND point.time >= replay.window_start
          AND point.time < replay.window_end
    ), 0),
    source_snapshot = pg_current_snapshot()
WHERE status IN ('COMPLETED', 'CANCELLED');

ALTER TABLE spatial_replay_request
    ADD CONSTRAINT ck_spatial_replay_request_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD', 'CANCELLED')
        AND attempts >= 0 AND scanned_points >= 0 AND emitted_events >= 0
    ),
    ADD CONSTRAINT ck_spatial_replay_request_completed CHECK (
        (status IN ('COMPLETED', 'DEAD', 'CANCELLED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'PROCESSING', 'FAILED') AND completed_at IS NULL)
    ),
    ADD CONSTRAINT ck_spatial_replay_request_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL
            AND locked_by IS NOT NULL AND btrim(locked_by) <> ''
            AND locked_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > locked_at AND started_at IS NOT NULL
            AND source_watermark IS NOT NULL AND source_snapshot IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL
            AND locked_by IS NULL AND locked_at IS NULL
            AND lease_expires_at IS NULL)
    ),
    ADD CONSTRAINT ck_spatial_replay_request_progress CHECK (
        (source_watermark IS NULL AND source_snapshot IS NULL AND status = 'PENDING'
            AND cursor_time IS NULL AND cursor_ingest_sequence IS NULL)
        OR (source_watermark >= 0 AND source_snapshot IS NOT NULL
            AND ((cursor_time IS NULL AND cursor_ingest_sequence IS NULL)
                OR (cursor_time IS NOT NULL AND cursor_ingest_sequence > 0)))
    ),
    ADD CONSTRAINT ck_spatial_replay_request_zone_snapshot CHECK (
        (zone_item_type IS NULL AND zone_content IS NULL
            AND zone_content_hash IS NULL AND zone_runtime_revision IS NULL)
        OR (zone_item_type = zone_type || '_VERSION'
            AND jsonb_typeof(zone_content) = 'object'
            AND pg_column_size(zone_content) <= 2097152
            AND zone_content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND zone_runtime_revision > 0)
    );

CREATE TABLE spatial_replay_subject_state (
    request_id              UUID        NOT NULL,
    organization_id         UUID        NOT NULL,
    site_id                 UUID        NOT NULL,
    tag_id                  UUID        NOT NULL,
    stable_state            VARCHAR(16) NOT NULL,
    candidate_state         VARCHAR(16),
    candidate_count         INTEGER     NOT NULL DEFAULT 0,
    entered_at              TIMESTAMPTZ,
    dwell_emitted_at        TIMESTAMPTZ,
    last_location_event_id  UUID        NOT NULL,
    last_occurred_at        TIMESTAMPTZ NOT NULL,
    last_ingest_sequence    BIGINT      NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_replay_subject_state PRIMARY KEY (request_id, tag_id),
    CONSTRAINT fk_spatial_replay_subject_state_request
        FOREIGN KEY (request_id) REFERENCES spatial_replay_request(request_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_spatial_replay_subject_state_location
        FOREIGN KEY (last_location_event_id) REFERENCES location_inbox(event_id),
    CONSTRAINT ck_spatial_replay_subject_state_stable
        CHECK (stable_state IN ('UNKNOWN', 'OUTSIDE', 'INSIDE')),
    CONSTRAINT ck_spatial_replay_subject_state_candidate CHECK (
        (candidate_state IS NULL AND candidate_count = 0)
        OR (candidate_state IN ('OUTSIDE', 'INSIDE') AND candidate_count > 0)
    ),
    CONSTRAINT ck_spatial_replay_subject_state_presence CHECK (
        (stable_state IN ('UNKNOWN', 'OUTSIDE')
            AND entered_at IS NULL AND dwell_emitted_at IS NULL)
        OR (stable_state = 'INSIDE' AND entered_at IS NOT NULL
            AND (dwell_emitted_at IS NULL OR dwell_emitted_at >= entered_at))
    ),
    CONSTRAINT ck_spatial_replay_subject_state_order CHECK (
        last_ingest_sequence > 0
    )
);

CREATE INDEX idx_location_point_spatial_replay
    ON location_point(organization_id, site_id, time, ingest_sequence);

-- 显式重放可处理从未进入在线评估队列的原始位置事实，正式事件仍锚定持久位置收件箱。
ALTER TABLE spatial_event
    DROP CONSTRAINT fk_spatial_event_anchor,
    ADD CONSTRAINT fk_spatial_event_anchor
        FOREIGN KEY (anchor_location_event_id) REFERENCES location_inbox(event_id);

COMMENT ON COLUMN spatial_replay_request.lease_token IS
    '每次认领随机生成且防止过期执行器覆盖新租约的令牌';
COMMENT ON COLUMN spatial_replay_request.source_watermark IS
    '首次认领时按全局摄入序列固化且后续批次不可扩大的位置事实水位';
COMMENT ON COLUMN spatial_replay_request.source_snapshot IS
    '首次认领时固化且跨批次判定事务可见性的 PostgreSQL 数据快照';
COMMENT ON COLUMN spatial_replay_request.cursor_time IS
    '最近一条已经提交处理的位置事实发生时间游标';
COMMENT ON COLUMN spatial_replay_request.cursor_ingest_sequence IS
    '同一发生时间下最近一条已经提交处理的位置事实摄入序号游标';
COMMENT ON COLUMN spatial_replay_request.zone_item_type IS
    '登记时优先冻结的途径点或电子围栏快照条目类型';
COMMENT ON COLUMN spatial_replay_request.zone_content IS
    '登记时优先从当前已激活定位快照冻结的不可变区域内容';
COMMENT ON COLUMN spatial_replay_request.zone_content_hash IS
    '冻结区域 JSON 规范化后计算并复核的 SHA-256 摘要';
COMMENT ON COLUMN spatial_replay_request.zone_runtime_revision IS
    '冻结区域内容来源的已激活定位运行快照修订号';
COMMENT ON COLUMN spatial_replay_request.status IS
    '等待、处理中、完成、可重试失败、重试耗尽死信或取消状态';

COMMENT ON TABLE spatial_replay_subject_state IS
    '按重放请求与定位标签隔离且可跨批次恢复的空间状态机状态';
COMMENT ON COLUMN spatial_replay_subject_state.request_id IS '所属空间历史重放请求标识';
COMMENT ON COLUMN spatial_replay_subject_state.organization_id IS '重放状态所属客户组织标识';
COMMENT ON COLUMN spatial_replay_subject_state.site_id IS '重放状态所属矿区标识';
COMMENT ON COLUMN spatial_replay_subject_state.tag_id IS '重放状态所属定位标签标识';
COMMENT ON COLUMN spatial_replay_subject_state.stable_state IS '当前未知、区域外或区域内稳定状态';
COMMENT ON COLUMN spatial_replay_subject_state.candidate_state IS '等待连续点确认的候选区域状态';
COMMENT ON COLUMN spatial_replay_subject_state.candidate_count IS '当前候选状态已连续确认的位置点数量';
COMMENT ON COLUMN spatial_replay_subject_state.entered_at IS '本次进入区域的事件时间';
COMMENT ON COLUMN spatial_replay_subject_state.dwell_emitted_at IS '本次进入已经发出持续停留事件的时间';
COMMENT ON COLUMN spatial_replay_subject_state.last_location_event_id IS '最近推进状态的位置事实事件标识';
COMMENT ON COLUMN spatial_replay_subject_state.last_occurred_at IS '最近推进状态的位置事实发生时间';
COMMENT ON COLUMN spatial_replay_subject_state.last_ingest_sequence IS '最近推进状态的位置事实摄入序号';
COMMENT ON COLUMN spatial_replay_subject_state.updated_at IS '重放状态最后更新时间';

COMMENT ON COLUMN spatial_event.event_sequence IS
    '按事务提交可见顺序串行分配的全局递增序号，用于固定历史查询分页水位';
COMMENT ON TABLE spatial_event_sequence_watermark IS
    '通过事务行锁串行分配空间事件提交可见顺序的单行水位';
COMMENT ON COLUMN spatial_event_sequence_watermark.singleton IS
    '恒为真的单行主键，确保全库空间事件序号串行分配';
COMMENT ON COLUMN spatial_event_sequence_watermark.last_sequence IS
    '最近一次已分配且随事务提交或回滚的空间事件序号';
COMMENT ON COLUMN spatial_event_sequence_watermark.updated_at IS
    '空间事件序号最近一次分配时间';
COMMENT ON FUNCTION assign_spatial_event_sequence() IS
    '在插入空间事件的同一事务内持有水位行锁并分配提交有序序号';

-- 重放登记账号仅能读取当前激活快照中的区域版本，避免暴露人员、车辆和绑定条目。
CREATE VIEW spatial_replay_active_zone_snapshot
WITH (security_barrier = TRUE) AS
SELECT stage.organization_id,
       stage.site_id,
       stage.item_key,
       stage.item_type,
       stage.content_json,
       stage.content_hash,
       runtime.runtime_revision
FROM location_runtime_revision runtime
JOIN location_snapshot_stage stage
  ON stage.snapshot_id = runtime.snapshot_id
 AND stage.organization_id = runtime.organization_id
 AND stage.site_id = runtime.site_id
 AND stage.runtime_revision = runtime.runtime_revision
WHERE stage.item_type IN ('WAYPOINT_VERSION', 'GEOFENCE_VERSION');

COMMENT ON VIEW spatial_replay_active_zone_snapshot IS
    '仅暴露当前激活定位快照中途径点和电子围栏版本的重放登记安全视图';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.organization_id IS '区域版本所属客户组织标识';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.site_id IS '区域版本所属矿区标识';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.item_key IS '区域版本类型与版本标识组成的快照条目键';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.item_type IS '途径点或电子围栏版本条目类型';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.content_json IS '区域版本不可变运行内容';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.content_hash IS '区域版本规范化内容 SHA-256 摘要';
COMMENT ON COLUMN spatial_replay_active_zone_snapshot.runtime_revision IS '区域版本所属当前激活运行修订号';

-- 单机部署会在迁移前创建托管最小权限角色，并通过数据库级自定义参数传入角色名。
DO $$
DECLARE
    replay_role TEXT := NULLIF(current_setting('mine.spatial_replay_role', TRUE), '');
BEGIN
    IF replay_role IS NOT NULL
            AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = replay_role) THEN
        EXECUTE format(
            'GRANT SELECT, INSERT ON TABLE spatial_replay_request TO %I', replay_role
        );
        EXECUTE format(
            'GRANT SELECT ON TABLE spatial_replay_active_zone_snapshot TO %I',
            replay_role
        );
    END IF;
END
$$;
