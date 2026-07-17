-- 显式聚合重算只登记有界、幂等、可审计的控制面事实，由 Telemetry 异步认领执行。
CREATE TABLE dev_aggregation_recompute_request (
    request_id      UUID          NOT NULL,
    device_id       UUID          NOT NULL,
    property_id     VARCHAR(128)  NOT NULL,
    bucket_seconds  INTEGER       NOT NULL,
    from_at         TIMESTAMPTZ   NOT NULL,
    to_at           TIMESTAMPTZ   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    requested_by    UUID          NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMPTZ,
    execution_id    UUID,
    last_error      VARCHAR(2000),
    CONSTRAINT pk_dev_aggregation_recompute_request PRIMARY KEY (request_id),
    CONSTRAINT uk_dev_aggregation_recompute_request_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT fk_dev_aggregation_recompute_request_device
        FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT fk_dev_aggregation_recompute_request_operator
        FOREIGN KEY (requested_by) REFERENCES sys_user (id),
    CONSTRAINT ck_dev_aggregation_recompute_property
        CHECK (property_id ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT ck_dev_aggregation_recompute_bucket
        CHECK (bucket_seconds IN (60, 300, 900, 3600, 21600, 86400)),
    CONSTRAINT ck_dev_aggregation_recompute_range CHECK (
        from_at < to_at
        AND EXTRACT(MICROSECONDS FROM from_at)::BIGINT % 1000000 = 0
        AND EXTRACT(MICROSECONDS FROM to_at)::BIGINT % 1000000 = 0
        AND EXTRACT(EPOCH FROM from_at)::BIGINT % bucket_seconds = 0
        AND EXTRACT(EPOCH FROM to_at)::BIGINT % bucket_seconds = 0
        AND to_at - from_at <= INTERVAL '31 days'
        AND EXTRACT(EPOCH FROM (to_at - from_at)) / bucket_seconds <= 10000
    ),
    CONSTRAINT ck_dev_aggregation_recompute_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_dev_aggregation_recompute_idempotency CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_dev_aggregation_recompute_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_dev_aggregation_recompute_lifecycle CHECK (
        (status = 'PENDING'
            AND completed_at IS NULL AND execution_id IS NULL AND last_error IS NULL)
        OR (status = 'RUNNING'
            AND completed_at IS NULL AND execution_id IS NOT NULL AND last_error IS NULL)
        OR (status = 'SUCCEEDED'
            AND completed_at IS NOT NULL AND execution_id IS NOT NULL AND last_error IS NULL)
        OR (status = 'FAILED'
            AND completed_at IS NOT NULL AND last_error IS NOT NULL AND btrim(last_error) <> '')
    )
);

CREATE INDEX idx_dev_aggregation_recompute_pending
    ON dev_aggregation_recompute_request (created_at, request_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_dev_aggregation_recompute_recent
    ON dev_aggregation_recompute_request (created_at DESC, request_id DESC);

COMMENT ON TABLE dev_aggregation_recompute_request IS '显式聚合重算请求控制面事实';
COMMENT ON COLUMN dev_aggregation_recompute_request.request_id IS '重算请求标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.device_id IS '目标设备标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.property_id IS '目标数值属性标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.bucket_seconds IS '聚合时间桶秒数';
COMMENT ON COLUMN dev_aggregation_recompute_request.from_at IS '重算开始时间（包含）';
COMMENT ON COLUMN dev_aggregation_recompute_request.to_at IS '重算结束时间（不包含）';
COMMENT ON COLUMN dev_aggregation_recompute_request.reason IS '人工重算原因';
COMMENT ON COLUMN dev_aggregation_recompute_request.idempotency_key IS '操作人范围内幂等键';
COMMENT ON COLUMN dev_aggregation_recompute_request.requested_by IS '申请人标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.status IS '待执行、运行中、成功或失败状态';
COMMENT ON COLUMN dev_aggregation_recompute_request.created_at IS '请求创建时间';
COMMENT ON COLUMN dev_aggregation_recompute_request.completed_at IS '请求完成时间';
COMMENT ON COLUMN dev_aggregation_recompute_request.execution_id IS 'Telemetry 聚合执行标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.last_error IS '脱敏后的最近错误摘要';

-- 直接发布聚合治理菜单和按钮权限，避免后端能力落地但角色菜单保持不变。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000520', '019b0000-0000-7000-8000-000000000500',
     '数值聚合', 'MENU', '/governance/aggregation', 'aggregation-governance',
     'governance:aggregation:view', 'mdi:chart-timeline-variant-shimmer', 20, TRUE,
     'mine.menu.aggregation', TRUE),
    ('019b0000-0000-7000-8000-000000000521', '019b0000-0000-7000-8000-000000000520',
     '执行聚合重算', 'BUTTON', NULL, NULL, 'governance:aggregation:execute',
     NULL, 10, FALSE, 'mine.action.aggregationRecompute', TRUE);

-- 既有策略查看角色获得聚合只读入口和页面依赖，不隐式扩大重算写权限。
INSERT INTO sys_role_menu (id, role_id, menu_id, created_at, created_by)
SELECT uuidv7(), granted.role_id, required.menu_id, CURRENT_TIMESTAMP, granted.created_by
FROM sys_role_menu granted
CROSS JOIN (VALUES
    ('019b0000-0000-7000-8000-000000000210'::UUID),
    ('019b0000-0000-7000-8000-000000000220'::UUID),
    ('019b0000-0000-7000-8000-000000000520'::UUID)
) AS required(menu_id)
WHERE granted.menu_id = '019b0000-0000-7000-8000-00000000024b'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 只有既有策略发布角色继承显式重算能力。
INSERT INTO sys_role_menu (id, role_id, menu_id, created_at, created_by)
SELECT uuidv7(), granted.role_id, '019b0000-0000-7000-8000-000000000521',
       CURRENT_TIMESTAMP, granted.created_by
FROM sys_role_menu granted
WHERE granted.menu_id = '019b0000-0000-7000-8000-00000000024d'
ON CONFLICT (role_id, menu_id) DO NOTHING;
