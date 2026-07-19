-- 空间事件运行时只引用已经持久化的位置事实，不复制高频坐标载荷。
CREATE TABLE spatial_evaluation_queue (
    location_event_id      UUID         NOT NULL,
    organization_id        UUID         NOT NULL,
    site_id                UUID         NOT NULL,
    tag_id                 UUID         NOT NULL,
    occurred_at            TIMESTAMPTZ  NOT NULL,
    ingest_sequence        BIGINT       NOT NULL,
    runtime_revision       BIGINT       NOT NULL,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result_reason          VARCHAR(48),
    attempts               INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token            UUID,
    locked_by              VARCHAR(128),
    locked_at              TIMESTAMPTZ,
    lease_expires_at       TIMESTAMPTZ,
    enqueued_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at           TIMESTAMPTZ,
    CONSTRAINT pk_spatial_evaluation_queue PRIMARY KEY (location_event_id),
    CONSTRAINT uk_spatial_evaluation_queue_ingest UNIQUE (ingest_sequence),
    CONSTRAINT uk_spatial_evaluation_queue_scope_event
        UNIQUE (organization_id, site_id, tag_id, location_event_id),
    CONSTRAINT fk_spatial_evaluation_queue_location_inbox
        FOREIGN KEY (location_event_id) REFERENCES location_inbox(event_id),
    CONSTRAINT ck_spatial_evaluation_queue_order
        CHECK (ingest_sequence > 0 AND runtime_revision > 0),
    CONSTRAINT ck_spatial_evaluation_queue_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'SKIPPED', 'FAILED', 'DEAD')
        AND attempts >= 0
    ),
    CONSTRAINT ck_spatial_evaluation_queue_result CHECK (
        (status IN ('PENDING', 'PROCESSING', 'PROCESSED') AND result_reason IS NULL)
        OR (status IN ('SKIPPED', 'FAILED', 'DEAD')
            AND result_reason IS NOT NULL AND btrim(result_reason) <> '')
    ),
    CONSTRAINT ck_spatial_evaluation_queue_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL
            AND locked_by IS NOT NULL AND btrim(locked_by) <> ''
            AND locked_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > locked_at)
        OR (status <> 'PROCESSING' AND lease_token IS NULL
            AND locked_by IS NULL AND locked_at IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_spatial_evaluation_queue_completed CHECK (
        (status IN ('PROCESSED', 'SKIPPED', 'DEAD') AND processed_at IS NOT NULL)
        OR (status IN ('PENDING', 'PROCESSING', 'FAILED') AND processed_at IS NULL)
    )
);

CREATE INDEX idx_spatial_evaluation_queue_claim
    ON spatial_evaluation_queue(status, next_attempt_at, occurred_at, ingest_sequence)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_spatial_evaluation_queue_expired_lease
    ON spatial_evaluation_queue(lease_expires_at, occurred_at, ingest_sequence)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_spatial_evaluation_queue_subject_order
    ON spatial_evaluation_queue(site_id, tag_id, occurred_at, ingest_sequence);

CREATE TABLE spatial_subject_watermark (
    organization_id        UUID         NOT NULL,
    site_id                UUID         NOT NULL,
    tag_id                 UUID         NOT NULL,
    max_occurred_at        TIMESTAMPTZ  NOT NULL,
    max_ingest_sequence    BIGINT       NOT NULL,
    last_arrival_at        TIMESTAMPTZ  NOT NULL,
    last_flushed_at        TIMESTAMPTZ,
    finalized_occurred_at  TIMESTAMPTZ,
    finalized_ingest_sequence BIGINT,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_subject_watermark PRIMARY KEY (site_id, tag_id),
    CONSTRAINT ck_spatial_subject_watermark_sequence CHECK (max_ingest_sequence > 0),
    CONSTRAINT ck_spatial_subject_watermark_flush
        CHECK (last_flushed_at IS NULL OR last_flushed_at <= updated_at),
    CONSTRAINT ck_spatial_subject_watermark_finalized CHECK (
        (finalized_occurred_at IS NULL AND finalized_ingest_sequence IS NULL)
        OR (finalized_occurred_at IS NOT NULL AND finalized_ingest_sequence > 0
            AND finalized_occurred_at <= max_occurred_at
            AND finalized_ingest_sequence <= max_ingest_sequence)
    )
);

CREATE INDEX idx_spatial_subject_watermark_idle
    ON spatial_subject_watermark(last_arrival_at, site_id, tag_id);

CREATE TABLE spatial_zone_state (
    organization_id        UUID         NOT NULL,
    site_id                UUID         NOT NULL,
    zone_type              VARCHAR(16)  NOT NULL,
    zone_version_id        UUID         NOT NULL,
    tag_id                 UUID         NOT NULL,
    stable_state           VARCHAR(16)  NOT NULL,
    candidate_state        VARCHAR(16),
    candidate_count        INTEGER      NOT NULL DEFAULT 0,
    entered_at             TIMESTAMPTZ,
    dwell_emitted_at       TIMESTAMPTZ,
    last_location_event_id UUID         NOT NULL,
    last_occurred_at       TIMESTAMPTZ  NOT NULL,
    last_ingest_sequence   BIGINT       NOT NULL,
    runtime_revision       BIGINT       NOT NULL,
    state_version          BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_zone_state
        PRIMARY KEY (site_id, zone_type, zone_version_id, tag_id),
    CONSTRAINT fk_spatial_zone_state_last_location FOREIGN KEY (
        organization_id, site_id, tag_id, last_location_event_id
    ) REFERENCES spatial_evaluation_queue(
        organization_id, site_id, tag_id, location_event_id
    ),
    CONSTRAINT ck_spatial_zone_state_type
        CHECK (zone_type IN ('WAYPOINT', 'GEOFENCE')),
    CONSTRAINT ck_spatial_zone_state_stable
        CHECK (stable_state IN ('UNKNOWN', 'OUTSIDE', 'INSIDE')),
    CONSTRAINT ck_spatial_zone_state_candidate CHECK (
        (candidate_state IS NULL AND candidate_count = 0)
        OR (candidate_state IN ('OUTSIDE', 'INSIDE') AND candidate_count > 0)
    ),
    CONSTRAINT ck_spatial_zone_state_presence CHECK (
        (stable_state IN ('UNKNOWN', 'OUTSIDE')
            AND entered_at IS NULL AND dwell_emitted_at IS NULL)
        OR (stable_state = 'INSIDE' AND entered_at IS NOT NULL
            AND (dwell_emitted_at IS NULL OR dwell_emitted_at >= entered_at))
    ),
    CONSTRAINT ck_spatial_zone_state_order CHECK (
        last_ingest_sequence > 0 AND runtime_revision > 0 AND state_version >= 0
    )
);

CREATE INDEX idx_spatial_zone_state_subject
    ON spatial_zone_state(site_id, tag_id, updated_at DESC);
CREATE INDEX idx_spatial_zone_state_zone
    ON spatial_zone_state(site_id, zone_type, zone_version_id, updated_at DESC);

CREATE TABLE spatial_event (
    event_id                 UUID         NOT NULL,
    organization_id          UUID         NOT NULL,
    site_id                  UUID         NOT NULL,
    zone_type                VARCHAR(16)  NOT NULL,
    zone_id                  UUID         NOT NULL,
    zone_version_id          UUID         NOT NULL,
    tag_id                   UUID         NOT NULL,
    vehicle_id               UUID,
    person_id                UUID,
    event_type               VARCHAR(16)  NOT NULL,
    anchor_location_event_id UUID         NOT NULL,
    previous_state           VARCHAR(16)  NOT NULL,
    current_state            VARCHAR(16)  NOT NULL,
    confirmation_points      INTEGER      NOT NULL,
    runtime_revision         BIGINT       NOT NULL,
    occurred_at              TIMESTAMPTZ  NOT NULL,
    evidence                 JSONB        NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_spatial_event PRIMARY KEY (event_id),
    CONSTRAINT uq_spatial_event_business UNIQUE (
        zone_type, zone_version_id, tag_id, event_type, anchor_location_event_id
    ),
    CONSTRAINT fk_spatial_event_anchor FOREIGN KEY (
        organization_id, site_id, tag_id, anchor_location_event_id
    ) REFERENCES spatial_evaluation_queue(
        organization_id, site_id, tag_id, location_event_id
    ),
    CONSTRAINT ck_spatial_event_zone_type
        CHECK (zone_type IN ('WAYPOINT', 'GEOFENCE')),
    CONSTRAINT ck_spatial_event_type
        CHECK (event_type IN ('ENTER', 'EXIT', 'DWELL')),
    CONSTRAINT ck_spatial_event_subject
        CHECK (vehicle_id IS NULL OR person_id IS NULL),
    CONSTRAINT ck_spatial_event_transition CHECK (
        (event_type = 'ENTER' AND previous_state = 'OUTSIDE' AND current_state = 'INSIDE')
        OR (event_type = 'EXIT' AND previous_state = 'INSIDE' AND current_state = 'OUTSIDE')
        OR (event_type = 'DWELL' AND previous_state = 'INSIDE' AND current_state = 'INSIDE')
    ),
    CONSTRAINT ck_spatial_event_evidence CHECK (
        confirmation_points > 0
        AND runtime_revision > 0
        AND jsonb_typeof(evidence) = 'object'
        AND pg_column_size(evidence) <= 16384
    )
);

CREATE INDEX idx_spatial_event_site_time
    ON spatial_event(site_id, occurred_at DESC, event_id DESC);
CREATE INDEX idx_spatial_event_zone_time
    ON spatial_event(site_id, zone_type, zone_id, occurred_at DESC);
CREATE INDEX idx_spatial_event_subject_time
    ON spatial_event(site_id, tag_id, occurred_at DESC);

CREATE TABLE spatial_event_outbox (
    event_id           UUID          NOT NULL,
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
    CONSTRAINT pk_spatial_event_outbox PRIMARY KEY (event_id),
    CONSTRAINT fk_spatial_event_outbox_event FOREIGN KEY (event_id)
        REFERENCES spatial_event(event_id),
    CONSTRAINT ck_spatial_event_outbox_type CHECK (
        event_type = 'realtime.spatial.event.created' AND schema_version = 1
    ),
    CONSTRAINT ck_spatial_event_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object' AND pg_column_size(payload) <= 32768
        AND payload_hash ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(headers) = 'object' AND pg_column_size(headers) <= 2048
    ),
    CONSTRAINT ck_spatial_event_outbox_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'DEAD')
        AND attempts >= 0
    ),
    CONSTRAINT ck_spatial_event_outbox_lease CHECK (
        (status = 'PUBLISHING' AND locked_by IS NOT NULL AND btrim(locked_by) <> ''
            AND locked_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > locked_at)
        OR (status <> 'PUBLISHING' AND locked_by IS NULL AND locked_at IS NULL
            AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_spatial_event_outbox_published
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_spatial_event_outbox_claim
    ON spatial_event_outbox(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_spatial_event_outbox_expired_lease
    ON spatial_event_outbox(lease_expires_at, created_at)
    WHERE status = 'PUBLISHING';

CREATE TABLE spatial_replay_request (
    request_id          UUID          NOT NULL,
    organization_id     UUID          NOT NULL,
    site_id             UUID          NOT NULL,
    zone_type           VARCHAR(16)   NOT NULL,
    zone_version_id     UUID          NOT NULL,
    requested_by        UUID          NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    reason              VARCHAR(1000) NOT NULL,
    window_start        TIMESTAMPTZ   NOT NULL,
    window_end          TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts            INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by           VARCHAR(128),
    locked_at           TIMESTAMPTZ,
    lease_expires_at    TIMESTAMPTZ,
    scanned_points      BIGINT        NOT NULL DEFAULT 0,
    emitted_events      BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    last_error          VARCHAR(2000),
    CONSTRAINT pk_spatial_replay_request PRIMARY KEY (request_id),
    CONSTRAINT uk_spatial_replay_request_key
        UNIQUE (organization_id, site_id, idempotency_key),
    CONSTRAINT ck_spatial_replay_request_zone_type
        CHECK (zone_type IN ('WAYPOINT', 'GEOFENCE')),
    CONSTRAINT ck_spatial_replay_request_key_reason CHECK (
        btrim(idempotency_key) <> '' AND btrim(reason) <> ''
    ),
    CONSTRAINT ck_spatial_replay_request_window CHECK (
        window_end > window_start AND window_end <= window_start + INTERVAL '31 days'
    ),
    CONSTRAINT ck_spatial_replay_request_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')
        AND attempts >= 0 AND scanned_points >= 0 AND emitted_events >= 0
    ),
    CONSTRAINT ck_spatial_replay_request_lease CHECK (
        (status = 'PROCESSING' AND locked_by IS NOT NULL AND btrim(locked_by) <> ''
            AND locked_at IS NOT NULL AND lease_expires_at IS NOT NULL
            AND lease_expires_at > locked_at AND started_at IS NOT NULL)
        OR (status <> 'PROCESSING' AND locked_by IS NULL AND locked_at IS NULL
            AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_spatial_replay_request_completed CHECK (
        (status IN ('COMPLETED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'PROCESSING', 'FAILED') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_spatial_replay_request_claim
    ON spatial_replay_request(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_spatial_replay_request_site_time
    ON spatial_replay_request(site_id, created_at DESC);

COMMENT ON TABLE spatial_evaluation_queue IS '等待按事件时间排序并执行空间判定的位置事实队列';
COMMENT ON COLUMN spatial_evaluation_queue.location_event_id IS '对应定位观测的稳定事件标识';
COMMENT ON COLUMN spatial_evaluation_queue.organization_id IS '队列记录所属客户组织标识';
COMMENT ON COLUMN spatial_evaluation_queue.site_id IS '队列记录所属矿区标识';
COMMENT ON COLUMN spatial_evaluation_queue.tag_id IS '队列记录所属定位标签标识';
COMMENT ON COLUMN spatial_evaluation_queue.occurred_at IS '位置事实在来源设备侧的发生时间';
COMMENT ON COLUMN spatial_evaluation_queue.ingest_sequence IS '位置事实的平台全局摄入序号';
COMMENT ON COLUMN spatial_evaluation_queue.runtime_revision IS '入队时激活的定位运行快照修订号';
COMMENT ON COLUMN spatial_evaluation_queue.status IS '等待、处理中、成功、跳过、失败或死信状态';
COMMENT ON COLUMN spatial_evaluation_queue.result_reason IS '跳过或失败时的固定低基数原因编码';
COMMENT ON COLUMN spatial_evaluation_queue.attempts IS '包含当前认领在内的处理尝试次数';
COMMENT ON COLUMN spatial_evaluation_queue.next_attempt_at IS '失败后允许再次认领的时间';
COMMENT ON COLUMN spatial_evaluation_queue.lease_token IS '每次领取随机生成且防止旧执行越权完成新租约的令牌';
COMMENT ON COLUMN spatial_evaluation_queue.locked_by IS '当前持有处理租约的服务实例标识';
COMMENT ON COLUMN spatial_evaluation_queue.locked_at IS '当前处理租约认领时间';
COMMENT ON COLUMN spatial_evaluation_queue.lease_expires_at IS '当前处理租约到期时间';
COMMENT ON COLUMN spatial_evaluation_queue.enqueued_at IS '位置事实加入空间评估队列的时间';
COMMENT ON COLUMN spatial_evaluation_queue.processed_at IS '队列记录进入最终状态的时间';

COMMENT ON TABLE spatial_subject_watermark IS '每个矿区标签用于迟到排序和空闲刷新的事件时间水位';
COMMENT ON COLUMN spatial_subject_watermark.organization_id IS '水位所属客户组织标识';
COMMENT ON COLUMN spatial_subject_watermark.site_id IS '水位所属矿区标识';
COMMENT ON COLUMN spatial_subject_watermark.tag_id IS '水位所属定位标签标识';
COMMENT ON COLUMN spatial_subject_watermark.max_occurred_at IS '目前观察到的最大位置事实发生时间';
COMMENT ON COLUMN spatial_subject_watermark.max_ingest_sequence IS '目前观察到的最大位置事实摄入序号';
COMMENT ON COLUMN spatial_subject_watermark.last_arrival_at IS '最近一条位置事实入队的可信平台时间';
COMMENT ON COLUMN spatial_subject_watermark.last_flushed_at IS '最近一次空闲刷新剩余记录的时间';
COMMENT ON COLUMN spatial_subject_watermark.finalized_occurred_at IS '已经确定不得回退的最近领取事实事件时间';
COMMENT ON COLUMN spatial_subject_watermark.finalized_ingest_sequence IS '最终事件时间前沿对应的位置事实摄入序号';
COMMENT ON COLUMN spatial_subject_watermark.updated_at IS '水位记录最后更新时间';

COMMENT ON TABLE spatial_zone_state IS '区域版本与定位标签之间可恢复的空间事件状态机状态';
COMMENT ON COLUMN spatial_zone_state.organization_id IS '状态所属客户组织标识';
COMMENT ON COLUMN spatial_zone_state.site_id IS '状态所属矿区标识';
COMMENT ON COLUMN spatial_zone_state.zone_type IS '途径点圆形区域或电子围栏区域类型';
COMMENT ON COLUMN spatial_zone_state.zone_version_id IS '参与判定的不可变区域发布版本标识';
COMMENT ON COLUMN spatial_zone_state.tag_id IS '参与判定的定位标签标识';
COMMENT ON COLUMN spatial_zone_state.stable_state IS '当前未知、区域外或区域内稳定状态';
COMMENT ON COLUMN spatial_zone_state.candidate_state IS '等待连续点确认的候选区域状态';
COMMENT ON COLUMN spatial_zone_state.candidate_count IS '当前候选状态已连续确认的位置点数量';
COMMENT ON COLUMN spatial_zone_state.entered_at IS '本次进入区域的事件时间';
COMMENT ON COLUMN spatial_zone_state.dwell_emitted_at IS '本次进入已经发出持续停留事件的时间';
COMMENT ON COLUMN spatial_zone_state.last_location_event_id IS '最近推进状态的位置事实事件标识';
COMMENT ON COLUMN spatial_zone_state.last_occurred_at IS '最近推进状态的位置事实发生时间';
COMMENT ON COLUMN spatial_zone_state.last_ingest_sequence IS '最近推进状态的位置事实摄入序号';
COMMENT ON COLUMN spatial_zone_state.runtime_revision IS '最近推进状态使用的定位运行快照修订号';
COMMENT ON COLUMN spatial_zone_state.state_version IS '状态并发更新使用的乐观版本号';
COMMENT ON COLUMN spatial_zone_state.created_at IS '状态记录首次创建时间';
COMMENT ON COLUMN spatial_zone_state.updated_at IS '状态记录最后更新时间';

COMMENT ON TABLE spatial_event IS '进入、离开和持续停留的不可变空间业务事件';
COMMENT ON COLUMN spatial_event.event_id IS '空间事件稳定 UUIDv7 标识';
COMMENT ON COLUMN spatial_event.organization_id IS '事件所属客户组织标识';
COMMENT ON COLUMN spatial_event.site_id IS '事件所属矿区标识';
COMMENT ON COLUMN spatial_event.zone_type IS '触发事件的途径点或电子围栏区域类型';
COMMENT ON COLUMN spatial_event.zone_id IS '触发事件的稳定区域对象标识';
COMMENT ON COLUMN spatial_event.zone_version_id IS '触发事件的不可变区域发布版本标识';
COMMENT ON COLUMN spatial_event.tag_id IS '触发事件的定位标签标识';
COMMENT ON COLUMN spatial_event.vehicle_id IS '发生时解析到的车辆标识';
COMMENT ON COLUMN spatial_event.person_id IS '发生时解析到的人员标识';
COMMENT ON COLUMN spatial_event.event_type IS '进入、离开或持续停留事件类型';
COMMENT ON COLUMN spatial_event.anchor_location_event_id IS '完成状态转换的锚点位置事实事件标识';
COMMENT ON COLUMN spatial_event.previous_state IS '空间事件产生前的稳定状态';
COMMENT ON COLUMN spatial_event.current_state IS '空间事件产生后的稳定状态';
COMMENT ON COLUMN spatial_event.confirmation_points IS '触发本次转换使用的连续确认点数量';
COMMENT ON COLUMN spatial_event.runtime_revision IS '判定本事件使用的定位运行快照修订号';
COMMENT ON COLUMN spatial_event.occurred_at IS '空间事件按位置事实确定的业务发生时间';
COMMENT ON COLUMN spatial_event.evidence IS '有界且结构化的事件坐标、分类和版本证据';
COMMENT ON COLUMN spatial_event.created_at IS '空间事件事务内持久化时间';

COMMENT ON TABLE spatial_event_outbox IS '与空间事件同事务创建并等待可靠发布的消息记录';
COMMENT ON COLUMN spatial_event_outbox.event_id IS '与空间业务事件一致且重试不变的事件标识';
COMMENT ON COLUMN spatial_event_outbox.event_type IS '允许发布的稳定实时空间事件类型';
COMMENT ON COLUMN spatial_event_outbox.schema_version IS '实时空间事件载荷结构版本';
COMMENT ON COLUMN spatial_event_outbox.payload IS '通过共享契约校验的完整实时空间事件载荷';
COMMENT ON COLUMN spatial_event_outbox.payload_hash IS '规范化实时空间事件载荷 SHA-256';
COMMENT ON COLUMN spatial_event_outbox.headers IS '分区键与追踪标识等有界消息头';
COMMENT ON COLUMN spatial_event_outbox.status IS '待发布、发布中、成功、失败或死信状态';
COMMENT ON COLUMN spatial_event_outbox.attempts IS '包含当前认领在内的发布尝试次数';
COMMENT ON COLUMN spatial_event_outbox.next_attempt_at IS '失败后允许再次认领的时间';
COMMENT ON COLUMN spatial_event_outbox.locked_by IS '当前持有发布租约的服务实例标识';
COMMENT ON COLUMN spatial_event_outbox.locked_at IS '当前发布租约认领时间';
COMMENT ON COLUMN spatial_event_outbox.lease_expires_at IS '当前发布租约到期时间';
COMMENT ON COLUMN spatial_event_outbox.occurred_at IS '实时空间业务事件发生时间';
COMMENT ON COLUMN spatial_event_outbox.created_at IS '事务内创建 Outbox 记录的时间';
COMMENT ON COLUMN spatial_event_outbox.published_at IS 'RabbitMQ 确认发布成功时间';
COMMENT ON COLUMN spatial_event_outbox.last_error IS '脱敏截断后的最近发布错误摘要';

COMMENT ON TABLE spatial_replay_request IS '管理员显式创建且幂等执行的空间历史重放请求';
COMMENT ON COLUMN spatial_replay_request.request_id IS '空间历史重放请求 UUIDv7 标识';
COMMENT ON COLUMN spatial_replay_request.organization_id IS '重放请求所属客户组织标识';
COMMENT ON COLUMN spatial_replay_request.site_id IS '重放请求所属矿区标识';
COMMENT ON COLUMN spatial_replay_request.zone_type IS '重放限定的途径点或电子围栏区域类型';
COMMENT ON COLUMN spatial_replay_request.zone_version_id IS '重放限定的不可变区域发布版本标识';
COMMENT ON COLUMN spatial_replay_request.requested_by IS '创建重放请求的管理员用户标识';
COMMENT ON COLUMN spatial_replay_request.idempotency_key IS '调用方在矿区范围内稳定的请求幂等键';
COMMENT ON COLUMN spatial_replay_request.reason IS '管理员填写的重放业务原因';
COMMENT ON COLUMN spatial_replay_request.window_start IS '重放位置事实时间窗起点';
COMMENT ON COLUMN spatial_replay_request.window_end IS '重放位置事实时间窗终点';
COMMENT ON COLUMN spatial_replay_request.status IS '等待、处理中、完成、失败或取消状态';
COMMENT ON COLUMN spatial_replay_request.attempts IS '包含当前认领在内的重放执行尝试次数';
COMMENT ON COLUMN spatial_replay_request.next_attempt_at IS '失败后允许再次认领的时间';
COMMENT ON COLUMN spatial_replay_request.locked_by IS '当前持有重放租约的服务实例标识';
COMMENT ON COLUMN spatial_replay_request.locked_at IS '当前重放租约认领时间';
COMMENT ON COLUMN spatial_replay_request.lease_expires_at IS '当前重放租约到期时间';
COMMENT ON COLUMN spatial_replay_request.scanned_points IS '本次重放已经扫描的位置事实数量';
COMMENT ON COLUMN spatial_replay_request.emitted_events IS '本次重放实际新建的空间事件数量';
COMMENT ON COLUMN spatial_replay_request.created_at IS '重放请求创建时间';
COMMENT ON COLUMN spatial_replay_request.started_at IS '重放请求首次开始执行时间';
COMMENT ON COLUMN spatial_replay_request.completed_at IS '重放请求进入完成或取消状态的时间';
COMMENT ON COLUMN spatial_replay_request.last_error IS '脱敏截断后的最近重放错误摘要';
