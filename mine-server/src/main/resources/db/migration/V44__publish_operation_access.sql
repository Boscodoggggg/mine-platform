-- 发布生产运营工作台、精确权限和持久动作幂等账本。

CREATE TABLE mine_operation_action_idempotency (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    site_id           UUID         NOT NULL,
    operator_id       UUID         NOT NULL,
    idempotency_key   VARCHAR(128) NOT NULL,
    action_type       VARCHAR(64)  NOT NULL,
    aggregate_type    VARCHAR(32)  NOT NULL,
    aggregate_id      UUID         NOT NULL,
    request_hash      VARCHAR(71)  NOT NULL,
    response_snapshot JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT pk_mine_operation_action_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_action_idempotency_key
        UNIQUE (organization_id, site_id, operator_id, idempotency_key),
    CONSTRAINT fk_mine_operation_action_idempotency_site
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_operation_action_idempotency_operator
        FOREIGN KEY (operator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_operation_action_idempotency_key
        CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_mine_operation_action_idempotency_action
        CHECK (btrim(action_type) <> '' AND btrim(aggregate_type) <> ''),
    CONSTRAINT ck_mine_operation_action_idempotency_hash
        CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mine_operation_action_idempotency_completion CHECK (
        (response_snapshot IS NULL AND completed_at IS NULL)
        OR (response_snapshot IS NOT NULL AND completed_at IS NOT NULL
            AND completed_at >= created_at
            AND jsonb_typeof(response_snapshot) = 'object'
            AND octet_length(response_snapshot::TEXT) <= 1048576)
    )
);

CREATE INDEX idx_mine_operation_action_idempotency_created
    ON mine_operation_action_idempotency(site_id, created_at, id);

COMMENT ON TABLE mine_operation_action_idempotency IS '生产运营用户动作持久幂等账本';
COMMENT ON COLUMN mine_operation_action_idempotency.id IS '幂等记录标识';
COMMENT ON COLUMN mine_operation_action_idempotency.organization_id IS '所属组织标识';
COMMENT ON COLUMN mine_operation_action_idempotency.site_id IS '所属矿区标识';
COMMENT ON COLUMN mine_operation_action_idempotency.operator_id IS '动作操作人标识';
COMMENT ON COLUMN mine_operation_action_idempotency.idempotency_key IS '调用方提供的幂等键';
COMMENT ON COLUMN mine_operation_action_idempotency.action_type IS '规范化动作类型';
COMMENT ON COLUMN mine_operation_action_idempotency.aggregate_type IS '业务聚合类型';
COMMENT ON COLUMN mine_operation_action_idempotency.aggregate_id IS '业务聚合标识';
COMMENT ON COLUMN mine_operation_action_idempotency.request_hash IS '规范化请求SHA-256摘要';
COMMENT ON COLUMN mine_operation_action_idempotency.response_snapshot IS '首次成功响应冻结快照';
COMMENT ON COLUMN mine_operation_action_idempotency.created_at IS '幂等请求认领时间';
COMMENT ON COLUMN mine_operation_action_idempotency.completed_at IS '首次动作完成时间';

INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000680', '019b0000-0000-7000-8000-000000000600',
     '生产运营', 'MENU', '/mine/operations', 'mine-operation-management',
     'mine:operation:view', 'mdi:clipboard-flow-outline', 80, TRUE,
     'mine.menu.mineOperations', TRUE),
    ('019b0000-0000-7000-8000-000000000681', '019b0000-0000-7000-8000-000000000680',
     '编辑班次', 'BUTTON', NULL, NULL, 'mine:shift:edit',
     NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000682', '019b0000-0000-7000-8000-000000000680',
     '编辑作业单', 'BUTTON', NULL, NULL, 'mine:work-order:edit',
     NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000683', '019b0000-0000-7000-8000-000000000680',
     '执行作业调度', 'BUTTON', NULL, NULL, 'mine:work-order:dispatch',
     NULL, 30, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000684', '019b0000-0000-7000-8000-000000000680',
     '追溯修正运营事实', 'BUTTON', NULL, NULL, 'mine:operation:correct',
     NULL, 40, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000685', '019b0000-0000-7000-8000-000000000680',
     '关闭生产班次', 'BUTTON', NULL, NULL, 'mine:shift:close',
     NULL, 50, FALSE, NULL, TRUE);
