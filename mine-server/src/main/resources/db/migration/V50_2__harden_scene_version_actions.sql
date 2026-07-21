-- 为场景草稿增加乐观锁，并建立独立的场景管理持久幂等账本。
ALTER TABLE mine_scene_version
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_mine_scene_version_optimistic_version CHECK (version >= 0);

COMMENT ON COLUMN mine_scene_version.version IS '场景版本草稿乐观锁版本';

CREATE TABLE mine_scene_action_idempotency (
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
    CONSTRAINT pk_mine_scene_action_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_action_idempotency_key
        UNIQUE (organization_id, site_id, operator_id, idempotency_key),
    CONSTRAINT fk_mine_scene_action_idempotency_site
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_scene_action_idempotency_operator
        FOREIGN KEY (operator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_scene_action_idempotency_key
        CHECK (btrim(idempotency_key) <> '' AND idempotency_key = btrim(idempotency_key)),
    CONSTRAINT ck_mine_scene_action_idempotency_action
        CHECK (action_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_mine_scene_action_idempotency_aggregate
        CHECK (aggregate_type ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_mine_scene_action_idempotency_hash
        CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mine_scene_action_idempotency_response
        CHECK (response_snapshot IS NULL OR jsonb_typeof(response_snapshot) = 'object'),
    CONSTRAINT ck_mine_scene_action_idempotency_completion CHECK (
        (response_snapshot IS NULL AND completed_at IS NULL)
        OR (response_snapshot IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= created_at)
    )
);

CREATE INDEX idx_mine_scene_action_idempotency_created
    ON mine_scene_action_idempotency(site_id, created_at, id);

COMMENT ON TABLE mine_scene_action_idempotency IS '场景发布、默认切换和归档动作的持久幂等账本';
COMMENT ON COLUMN mine_scene_action_idempotency.id IS '场景幂等记录标识';
COMMENT ON COLUMN mine_scene_action_idempotency.organization_id IS '幂等动作所属组织标识';
COMMENT ON COLUMN mine_scene_action_idempotency.site_id IS '幂等动作所属矿区标识';
COMMENT ON COLUMN mine_scene_action_idempotency.operator_id IS '发起幂等动作的用户标识';
COMMENT ON COLUMN mine_scene_action_idempotency.idempotency_key IS '调用方提供的场景动作幂等键';
COMMENT ON COLUMN mine_scene_action_idempotency.action_type IS '规范化场景动作类型';
COMMENT ON COLUMN mine_scene_action_idempotency.aggregate_type IS '场景业务聚合类型';
COMMENT ON COLUMN mine_scene_action_idempotency.aggregate_id IS '场景业务聚合标识';
COMMENT ON COLUMN mine_scene_action_idempotency.request_hash IS '规范化动作请求的 SHA-256 摘要';
COMMENT ON COLUMN mine_scene_action_idempotency.response_snapshot IS '首次成功响应的不可变 JSON 快照';
COMMENT ON COLUMN mine_scene_action_idempotency.created_at IS '幂等动作认领时间';
COMMENT ON COLUMN mine_scene_action_idempotency.completed_at IS '首次动作完成时间';
