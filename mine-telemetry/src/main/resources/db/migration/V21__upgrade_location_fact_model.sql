-- 位置事实原位升级，保留既有 hypertable、历史分区及事件唯一索引。
ALTER TABLE location_point RENAME COLUMN device_id TO source_device_id;
ALTER TABLE location_point RENAME COLUMN x TO local_x;
ALTER TABLE location_point RENAME COLUMN y TO local_y;
ALTER TABLE location_point RENAME COLUMN z TO local_z;
ALTER TABLE location_point RENAME COLUMN speed TO speed_meters_per_second;
ALTER TABLE location_point RENAME COLUMN heading TO heading_degrees;
ALTER TABLE location_point RENAME COLUMN coordinate_system_id TO target_coordinate_system_id;

ALTER TABLE location_point
    ALTER COLUMN quality TYPE VARCHAR(16)
        USING CASE quality WHEN 0 THEN 'GOOD' ELSE 'DEGRADED' END;
ALTER TABLE location_point RENAME COLUMN quality TO quality_status;

CREATE SEQUENCE location_ingest_sequence_seq AS BIGINT;

ALTER TABLE location_point
    ADD COLUMN organization_id UUID,
    ADD COLUMN site_id UUID,
    ADD COLUMN source_coordinate_system_id UUID,
    ADD COLUMN source_x DOUBLE PRECISION,
    ADD COLUMN source_y DOUBLE PRECISION,
    ADD COLUMN source_z DOUBLE PRECISION,
    ADD COLUMN transform_version_id UUID,
    ADD COLUMN binding_type VARCHAR(32),
    ADD COLUMN binding_id UUID,
    ADD COLUMN vehicle_id UUID,
    ADD COLUMN person_id UUID,
    ADD COLUMN accuracy_meters DOUBLE PRECISION,
    ADD COLUMN quality_reason VARCHAR(128),
    ADD COLUMN ingest_sequence BIGINT;

-- 升级前数据没有原始坐标和主数据快照，只保留可证明的既有统一坐标事实。
UPDATE location_point
SET ingest_sequence = nextval('location_ingest_sequence_seq')
WHERE ingest_sequence IS NULL;

ALTER TABLE location_point
    ALTER COLUMN ingest_sequence SET DEFAULT nextval('location_ingest_sequence_seq'),
    ALTER COLUMN ingest_sequence SET NOT NULL,
    DROP CONSTRAINT IF EXISTS location_point_boot_id_check,
    ADD CONSTRAINT ck_location_point_boot_id
        CHECK (boot_id IS NULL OR char_length(boot_id) BETWEEN 1 AND 128),
    ADD CONSTRAINT ck_location_point_source_sequence
        CHECK (sequence IS NULL OR sequence > 0),
    ADD CONSTRAINT ck_location_point_ingest_sequence
        CHECK (ingest_sequence > 0),
    ADD CONSTRAINT ck_location_point_quality CHECK (
        quality_status IN ('GOOD', 'DEGRADED')
        AND (
            (quality_status = 'GOOD' AND quality_reason IS NULL)
            OR
            (quality_status = 'DEGRADED'
                AND quality_reason IS NOT NULL AND btrim(quality_reason) <> '')
        )
    ) NOT VALID,
    ADD CONSTRAINT ck_location_point_numeric_values CHECK (
        local_x NOT IN ('NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION,
                        '-Infinity'::DOUBLE PRECISION)
        AND local_y NOT IN ('NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION,
                            '-Infinity'::DOUBLE PRECISION)
        AND (local_z IS NULL OR local_z NOT IN (
            'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION))
        AND (source_x IS NULL OR source_x NOT IN (
            'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION))
        AND (source_y IS NULL OR source_y NOT IN (
            'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION))
        AND (source_z IS NULL OR source_z NOT IN (
            'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION))
        AND (speed_meters_per_second IS NULL OR (
            speed_meters_per_second >= 0
            AND speed_meters_per_second NOT IN (
                'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION)))
        AND (heading_degrees IS NULL OR (
            heading_degrees >= 0 AND heading_degrees < 360
            AND heading_degrees NOT IN (
                'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION)))
        AND (accuracy_meters IS NULL OR (
            accuracy_meters >= 0
            AND accuracy_meters NOT IN (
                'NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION, '-Infinity'::DOUBLE PRECISION)))
    ) NOT VALID,
    ADD CONSTRAINT ck_location_point_binding CHECK (
        (binding_type IS NULL AND binding_id IS NULL AND vehicle_id IS NULL AND person_id IS NULL)
        OR
        (binding_type = 'VEHICLE_TAG'
            AND binding_id IS NOT NULL AND vehicle_id IS NOT NULL AND person_id IS NULL)
        OR
        (binding_type = 'PERSON_TAG'
            AND binding_id IS NOT NULL AND vehicle_id IS NULL AND person_id IS NOT NULL)
    ) NOT VALID,
    -- NOT VALID 只豁免无法补齐快照证据的历史行，迁移后的新增和更新仍必须满足约束。
    ADD CONSTRAINT ck_location_point_runtime_fact CHECK (
        organization_id IS NOT NULL
        AND site_id IS NOT NULL
        AND source_device_id IS NOT NULL
        AND source_coordinate_system_id IS NOT NULL
        AND source_x IS NOT NULL
        AND source_y IS NOT NULL
        AND target_coordinate_system_id IS NOT NULL
        AND transform_version_id IS NOT NULL
        AND binding_type IS NOT NULL
        AND binding_id IS NOT NULL
        AND boot_id IS NOT NULL
        AND sequence IS NOT NULL
    ) NOT VALID;

ALTER SEQUENCE location_ingest_sequence_seq OWNED BY location_point.ingest_sequence;

CREATE UNIQUE INDEX uq_location_source_cycle_time
    ON location_point(source_device_id, boot_id, sequence, time)
    WHERE source_device_id IS NOT NULL AND boot_id IS NOT NULL AND sequence IS NOT NULL;
CREATE INDEX idx_location_site_time
    ON location_point(site_id, time DESC)
    WHERE site_id IS NOT NULL;
CREATE INDEX idx_location_site_tag_time
    ON location_point(site_id, tag_id, time DESC)
    WHERE site_id IS NOT NULL;
CREATE INDEX idx_location_site_vehicle_time
    ON location_point(site_id, vehicle_id, time DESC)
    WHERE site_id IS NOT NULL AND vehicle_id IS NOT NULL;
CREATE INDEX idx_location_site_person_time
    ON location_point(site_id, person_id, time DESC)
    WHERE site_id IS NOT NULL AND person_id IS NOT NULL;

-- Redis 当前位置丢失后按全局摄入序号重建，水位防止旧任务覆盖新结果。
CREATE TABLE location_current_rebuild_watermark (
    site_id                UUID        NOT NULL,
    organization_id        UUID        NOT NULL,
    last_ingest_sequence   BIGINT      NOT NULL DEFAULT 0,
    last_event_id          UUID,
    rebuilt_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_location_current_rebuild_watermark PRIMARY KEY (site_id),
    CONSTRAINT ck_location_current_rebuild_watermark_sequence
        CHECK (last_ingest_sequence >= 0),
    CONSTRAINT ck_location_current_rebuild_watermark_event CHECK (
        (last_ingest_sequence = 0 AND last_event_id IS NULL)
        OR (last_ingest_sequence > 0 AND last_event_id IS NOT NULL)
    )
);

-- 快照条目先完整暂存，验证数量和摘要后才允许切换最后正确运行修订。
CREATE TABLE location_snapshot_stage (
    snapshot_id            UUID         NOT NULL,
    organization_id        UUID         NOT NULL,
    site_id                UUID         NOT NULL,
    runtime_revision       BIGINT       NOT NULL,
    item_key               VARCHAR(128) NOT NULL,
    item_type              VARCHAR(48)  NOT NULL,
    content_json           JSONB        NOT NULL,
    content_hash           VARCHAR(71)  NOT NULL,
    expected_item_count    INTEGER      NOT NULL,
    snapshot_content_hash  VARCHAR(71)  NOT NULL,
    source_created_at      TIMESTAMPTZ  NOT NULL,
    staged_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_location_snapshot_stage PRIMARY KEY (snapshot_id, item_key),
    CONSTRAINT ck_location_snapshot_stage_revision
        CHECK (runtime_revision > 0 AND expected_item_count > 0),
    CONSTRAINT ck_location_snapshot_stage_key
        CHECK (btrim(item_key) <> ''),
    CONSTRAINT ck_location_snapshot_stage_type CHECK (item_type IN (
        'SITE', 'VEHICLE', 'PERSON', 'LOCATION_TAG', 'DEVICE',
        'COORDINATE_SYSTEM', 'COORDINATE_TRANSFORM',
        'VEHICLE_DEVICE_BINDING', 'VEHICLE_TAG_BINDING',
        'PERSON_TAG_BINDING', 'PERSON_VEHICLE_BINDING'
    )),
    CONSTRAINT ck_location_snapshot_stage_content CHECK (
        jsonb_typeof(content_json) = 'object'
        AND pg_column_size(content_json) <= 1048576
        AND content_hash ~ '^sha256:[0-9a-f]{64}$'
        AND snapshot_content_hash ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_location_snapshot_stage_site_revision
    ON location_snapshot_stage(site_id, runtime_revision, snapshot_id);
CREATE INDEX idx_location_snapshot_stage_time
    ON location_snapshot_stage(staged_at);

CREATE TABLE location_runtime_revision (
    site_id                UUID        NOT NULL,
    organization_id        UUID        NOT NULL,
    runtime_revision       BIGINT      NOT NULL,
    snapshot_id            UUID        NOT NULL,
    item_count             INTEGER     NOT NULL,
    content_hash           VARCHAR(71) NOT NULL,
    source_created_at      TIMESTAMPTZ NOT NULL,
    applied_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_location_runtime_revision PRIMARY KEY (site_id),
    CONSTRAINT uk_location_runtime_revision_snapshot UNIQUE (snapshot_id),
    CONSTRAINT ck_location_runtime_revision_number
        CHECK (runtime_revision > 0 AND item_count > 0),
    CONSTRAINT ck_location_runtime_revision_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

-- 事件级和设备启动周期级幂等均在写位置事实前完成。
CREATE TABLE location_inbox (
    event_id           UUID        NOT NULL,
    source_device_id   UUID        NOT NULL,
    boot_id            VARCHAR(128) NOT NULL,
    source_sequence    BIGINT      NOT NULL,
    payload_hash       VARCHAR(71) NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL,
    result             VARCHAR(16) NOT NULL,
    ingest_sequence    BIGINT,
    processed_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_location_inbox PRIMARY KEY (event_id),
    CONSTRAINT uk_location_inbox_source_cycle
        UNIQUE (source_device_id, boot_id, source_sequence),
    CONSTRAINT ck_location_inbox_source
        CHECK (char_length(boot_id) BETWEEN 1 AND 128 AND source_sequence > 0),
    CONSTRAINT ck_location_inbox_hash
        CHECK (payload_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_location_inbox_result CHECK (
        (result = 'STORED' AND ingest_sequence IS NOT NULL AND ingest_sequence > 0)
        OR (result = 'QUARANTINED' AND ingest_sequence IS NULL)
    )
);

CREATE INDEX idx_location_inbox_received_at ON location_inbox(received_at);

-- 隔离区只保存有界诊断证据并设置强制到期时间，避免异常载荷形成无上限存储。
CREATE TABLE location_quarantine (
    quarantine_id      UUID        NOT NULL,
    event_id           UUID        NOT NULL,
    organization_id    UUID,
    site_id            UUID,
    source_device_id   UUID        NOT NULL,
    reason_code        VARCHAR(48) NOT NULL,
    payload_hash       VARCHAR(71) NOT NULL,
    evidence           JSONB       NOT NULL DEFAULT '{}'::JSONB,
    occurred_at        TIMESTAMPTZ NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at         TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '7 days'),
    CONSTRAINT pk_location_quarantine PRIMARY KEY (quarantine_id),
    CONSTRAINT uk_location_quarantine_event UNIQUE (event_id),
    CONSTRAINT fk_location_quarantine_inbox FOREIGN KEY (event_id)
        REFERENCES location_inbox(event_id),
    CONSTRAINT ck_location_quarantine_scope CHECK (
        (organization_id IS NULL AND site_id IS NULL)
        OR (organization_id IS NOT NULL AND site_id IS NOT NULL)
    ),
    CONSTRAINT ck_location_quarantine_reason CHECK (reason_code IN (
        'UNKNOWN_TAG', 'ARCHIVED_TAG', 'SOURCE_DEVICE_UNAUTHORIZED',
        'NON_FINITE_COORDINATE', 'OUT_OF_BOUNDS', 'TRANSFORM_MISSING',
        'TRANSFORM_VERSION_CONFLICT', 'SNAPSHOT_UNAVAILABLE', 'SNAPSHOT_INVALID',
        'INVALID_OBSERVATION'
    )),
    CONSTRAINT ck_location_quarantine_evidence_and_expiry CHECK (
        jsonb_typeof(evidence) = 'object'
        AND pg_column_size(evidence) <= 4096
        AND payload_hash ~ '^sha256:[0-9a-f]{64}$'
        AND expires_at > created_at
        AND expires_at <= created_at + INTERVAL '30 days'
    )
);

CREATE INDEX idx_location_quarantine_expires_at
    ON location_quarantine(expires_at);
CREATE INDEX idx_location_quarantine_site_received_at
    ON location_quarantine(site_id, received_at DESC)
    WHERE site_id IS NOT NULL;

-- 位置事实和实时事件在同一事务落库，由有界租约发布器可靠转发。
CREATE TABLE location_realtime_outbox (
    event_id           UUID          NOT NULL,
    ingest_sequence    BIGINT        NOT NULL,
    event_type         VARCHAR(64)   NOT NULL,
    schema_version     INTEGER       NOT NULL,
    payload            JSONB         NOT NULL,
    payload_hash       VARCHAR(71)   NOT NULL,
    headers            JSONB         NOT NULL DEFAULT '{}'::JSONB,
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts           INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by          VARCHAR(128),
    locked_at          TIMESTAMPTZ,
    lease_expires_at   TIMESTAMPTZ,
    occurred_at        TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at       TIMESTAMPTZ,
    last_error         VARCHAR(2000),
    CONSTRAINT pk_location_realtime_outbox PRIMARY KEY (event_id),
    CONSTRAINT uk_location_realtime_outbox_sequence UNIQUE (ingest_sequence),
    CONSTRAINT fk_location_realtime_outbox_inbox FOREIGN KEY (event_id)
        REFERENCES location_inbox(event_id),
    CONSTRAINT ck_location_realtime_outbox_type
        CHECK (event_type = 'realtime.location.updated' AND schema_version = 1),
    CONSTRAINT ck_location_realtime_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND pg_column_size(payload) <= 16384
        AND payload_hash ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(headers) = 'object'
        AND pg_column_size(headers) <= 2048
    ),
    CONSTRAINT ck_location_realtime_outbox_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'DEAD')
        AND attempts >= 0
    ),
    CONSTRAINT ck_location_realtime_outbox_lease CHECK (
        (status = 'PUBLISHING' AND locked_by IS NOT NULL AND locked_at IS NOT NULL
            AND lease_expires_at IS NOT NULL AND lease_expires_at > locked_at)
        OR
        (status <> 'PUBLISHING' AND locked_by IS NULL AND locked_at IS NULL
            AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_location_realtime_outbox_published
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_location_realtime_outbox_pending
    ON location_realtime_outbox(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_location_realtime_outbox_expired_lease
    ON location_realtime_outbox(lease_expires_at, created_at)
    WHERE status = 'PUBLISHING';
CREATE INDEX idx_location_realtime_outbox_published
    ON location_realtime_outbox(published_at)
    WHERE published_at IS NOT NULL;

COMMENT ON TABLE location_point IS '定位观测经绑定解析和版本化坐标转换后形成的双坐标历史事实';
COMMENT ON COLUMN location_point.time IS '定位观测在来源设备侧的发生时间（UTC）';
COMMENT ON COLUMN location_point.source_device_id IS '发布本次定位观测的可信来源设备标识';
COMMENT ON COLUMN location_point.tag_id IS '发生时解析到的定位标签标识';
COMMENT ON COLUMN location_point.local_x IS '转换后的矿区统一坐标系 X 轴坐标（米）';
COMMENT ON COLUMN location_point.local_y IS '转换后的矿区统一坐标系 Y 轴坐标（米）';
COMMENT ON COLUMN location_point.local_z IS '转换后的矿区统一坐标系 Z 轴坐标（米）';
COMMENT ON COLUMN location_point.speed_meters_per_second IS '观测移动速度（米每秒）';
COMMENT ON COLUMN location_point.heading_degrees IS '观测航向角（度，正北为零顺时针）';
COMMENT ON COLUMN location_point.quality_status IS '固定位置质量状态：正常或降级';
COMMENT ON COLUMN location_point.target_coordinate_system_id IS '统一矿区目标坐标系标识';
COMMENT ON COLUMN location_point.boot_id IS '来源设备本次启动周期标识';
COMMENT ON COLUMN location_point.sequence IS '来源设备启动周期内严格递增序号';
COMMENT ON COLUMN location_point.event_id IS '重试保持不变的定位观测 UUIDv7 事件标识';
COMMENT ON COLUMN location_point.received_at IS '平台可信接收时间（UTC）';
COMMENT ON COLUMN location_point.organization_id IS '事实所属客户组织标识';
COMMENT ON COLUMN location_point.site_id IS '事实所属矿区标识';
COMMENT ON COLUMN location_point.source_coordinate_system_id IS '来源设备上报坐标所使用的坐标系标识';
COMMENT ON COLUMN location_point.source_x IS '未经转换的来源坐标 X 分量';
COMMENT ON COLUMN location_point.source_y IS '未经转换的来源坐标 Y 分量';
COMMENT ON COLUMN location_point.source_z IS '未经转换的来源坐标 Z 分量';
COMMENT ON COLUMN location_point.transform_version_id IS '本事实使用的不可变坐标转换版本标识';
COMMENT ON COLUMN location_point.binding_type IS '发生时生效的车辆标签或人员标签绑定类型';
COMMENT ON COLUMN location_point.binding_id IS '发生时生效的时间绑定记录标识';
COMMENT ON COLUMN location_point.vehicle_id IS '发生时解析到的车辆标识，仅车辆标签事实存在';
COMMENT ON COLUMN location_point.person_id IS '发生时解析到的人员标识，仅人员标签事实存在';
COMMENT ON COLUMN location_point.accuracy_meters IS '来源设备声明的位置精度半径（米）';
COMMENT ON COLUMN location_point.quality_reason IS '降级位置事实的固定原因编码';
COMMENT ON COLUMN location_point.ingest_sequence IS '平台分配的全局单调摄入序号';

COMMENT ON TABLE location_current_rebuild_watermark IS '按矿区重建 Redis 当前位置时使用的持久顺序水位';
COMMENT ON COLUMN location_current_rebuild_watermark.site_id IS '重建水位所属矿区标识';
COMMENT ON COLUMN location_current_rebuild_watermark.organization_id IS '重建水位所属客户组织标识';
COMMENT ON COLUMN location_current_rebuild_watermark.last_ingest_sequence IS '最近完成重建的位置事实摄入序号';
COMMENT ON COLUMN location_current_rebuild_watermark.last_event_id IS '最近完成重建的位置事实事件标识';
COMMENT ON COLUMN location_current_rebuild_watermark.rebuilt_at IS '最近一次成功完成重建的时间';
COMMENT ON COLUMN location_current_rebuild_watermark.updated_at IS '重建水位最后更新时间';

COMMENT ON TABLE location_snapshot_stage IS '尚未完成整包校验的定位运行快照暂存条目';
COMMENT ON COLUMN location_snapshot_stage.snapshot_id IS '服务端不可变定位运行快照标识';
COMMENT ON COLUMN location_snapshot_stage.organization_id IS '快照所属客户组织标识';
COMMENT ON COLUMN location_snapshot_stage.site_id IS '快照所属矿区标识';
COMMENT ON COLUMN location_snapshot_stage.runtime_revision IS '矿区定位运行快照单调修订号';
COMMENT ON COLUMN location_snapshot_stage.item_key IS '快照条目的稳定类型与资源联合键';
COMMENT ON COLUMN location_snapshot_stage.item_type IS '快照条目的固定资源类型';
COMMENT ON COLUMN location_snapshot_stage.content_json IS '规范化后的单条快照内容';
COMMENT ON COLUMN location_snapshot_stage.content_hash IS '单条规范化快照内容 SHA-256';
COMMENT ON COLUMN location_snapshot_stage.expected_item_count IS '完整快照声明的条目总数';
COMMENT ON COLUMN location_snapshot_stage.snapshot_content_hash IS '完整规范化快照内容 SHA-256';
COMMENT ON COLUMN location_snapshot_stage.source_created_at IS '服务端创建不可变快照的时间';
COMMENT ON COLUMN location_snapshot_stage.staged_at IS '本条目最后暂存时间';

COMMENT ON TABLE location_runtime_revision IS 'Telemetry 已原子激活的矿区最后正确定位运行修订';
COMMENT ON COLUMN location_runtime_revision.site_id IS '已激活修订所属矿区标识';
COMMENT ON COLUMN location_runtime_revision.organization_id IS '已激活修订所属客户组织标识';
COMMENT ON COLUMN location_runtime_revision.runtime_revision IS '已激活的矿区单调运行修订号';
COMMENT ON COLUMN location_runtime_revision.snapshot_id IS '已激活的服务端不可变快照标识';
COMMENT ON COLUMN location_runtime_revision.item_count IS '已校验并激活的快照条目数';
COMMENT ON COLUMN location_runtime_revision.content_hash IS '已校验的完整快照内容 SHA-256';
COMMENT ON COLUMN location_runtime_revision.source_created_at IS '服务端创建不可变快照的时间';
COMMENT ON COLUMN location_runtime_revision.applied_at IS '本修订完成原子切换的时间';

COMMENT ON TABLE location_inbox IS '定位观测完成事件和启动周期幂等处理的持久凭据';
COMMENT ON COLUMN location_inbox.event_id IS '定位观测 UUIDv7 事件标识';
COMMENT ON COLUMN location_inbox.source_device_id IS '定位观测可信来源设备标识';
COMMENT ON COLUMN location_inbox.boot_id IS '来源设备启动周期标识';
COMMENT ON COLUMN location_inbox.source_sequence IS '来源设备启动周期内消息序号';
COMMENT ON COLUMN location_inbox.payload_hash IS '规范化定位观测载荷 SHA-256';
COMMENT ON COLUMN location_inbox.occurred_at IS '定位观测业务发生时间';
COMMENT ON COLUMN location_inbox.received_at IS '平台可信接收时间';
COMMENT ON COLUMN location_inbox.result IS '位置事实已存储或观测已隔离的处理结果';
COMMENT ON COLUMN location_inbox.ingest_sequence IS '成功位置事实的平台全局摄入序号';
COMMENT ON COLUMN location_inbox.processed_at IS '定位观测完成事务处理的时间';

COMMENT ON TABLE location_quarantine IS '无法安全形成位置事实的有界定位观测诊断证据';
COMMENT ON COLUMN location_quarantine.quarantine_id IS '隔离记录 UUIDv7 标识';
COMMENT ON COLUMN location_quarantine.event_id IS '被隔离的定位观测事件标识';
COMMENT ON COLUMN location_quarantine.organization_id IS '可以可信解析时的客户组织标识';
COMMENT ON COLUMN location_quarantine.site_id IS '可以可信解析时的矿区标识';
COMMENT ON COLUMN location_quarantine.source_device_id IS '被隔离观测的可信来源设备标识';
COMMENT ON COLUMN location_quarantine.reason_code IS '固定低基数隔离原因编码';
COMMENT ON COLUMN location_quarantine.payload_hash IS '规范化定位观测载荷 SHA-256';
COMMENT ON COLUMN location_quarantine.evidence IS '脱敏且限制大小的结构化诊断证据';
COMMENT ON COLUMN location_quarantine.occurred_at IS '被隔离观测的业务发生时间';
COMMENT ON COLUMN location_quarantine.received_at IS '平台可信接收被隔离观测的时间';
COMMENT ON COLUMN location_quarantine.created_at IS '隔离记录创建时间';
COMMENT ON COLUMN location_quarantine.expires_at IS '隔离诊断证据强制清理截止时间';

COMMENT ON TABLE location_realtime_outbox IS '与位置事实同事务创建并等待可靠发布的实时位置事件';
COMMENT ON COLUMN location_realtime_outbox.event_id IS '与位置事实一致且重试不变的事件标识';
COMMENT ON COLUMN location_realtime_outbox.ingest_sequence IS '实时事件对应的位置事实全局摄入序号';
COMMENT ON COLUMN location_realtime_outbox.event_type IS '稳定实时位置业务事件类型';
COMMENT ON COLUMN location_realtime_outbox.schema_version IS '实时位置事件载荷结构版本';
COMMENT ON COLUMN location_realtime_outbox.payload IS '通过共享契约校验的完整实时位置载荷';
COMMENT ON COLUMN location_realtime_outbox.payload_hash IS '规范化实时位置载荷 SHA-256';
COMMENT ON COLUMN location_realtime_outbox.headers IS '分区键与追踪标识等有界消息头';
COMMENT ON COLUMN location_realtime_outbox.status IS '待发布、发布中、成功、失败或死信状态';
COMMENT ON COLUMN location_realtime_outbox.attempts IS '包含当前认领在内的发布尝试次数';
COMMENT ON COLUMN location_realtime_outbox.next_attempt_at IS '失败后允许再次认领的时间';
COMMENT ON COLUMN location_realtime_outbox.locked_by IS '当前持有发布租约的服务实例标识';
COMMENT ON COLUMN location_realtime_outbox.locked_at IS '当前发布租约认领时间';
COMMENT ON COLUMN location_realtime_outbox.lease_expires_at IS '当前发布租约到期时间';
COMMENT ON COLUMN location_realtime_outbox.occurred_at IS '实时位置业务事实发生时间';
COMMENT ON COLUMN location_realtime_outbox.created_at IS '事务内创建实时位置事件的时间';
COMMENT ON COLUMN location_realtime_outbox.published_at IS 'RabbitMQ 确认发布成功时间';
COMMENT ON COLUMN location_realtime_outbox.last_error IS '脱敏截断后的最近发布错误摘要';
