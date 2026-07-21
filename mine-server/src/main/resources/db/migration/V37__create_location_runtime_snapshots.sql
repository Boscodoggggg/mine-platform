-- 建立定位热路径按矿区发布的不可变完整运行快照。
CREATE TABLE mine_location_runtime_snapshot (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    revision        BIGINT      NOT NULL,
    change_reason   VARCHAR(32) NOT NULL,
    item_count      INTEGER     NOT NULL,
    content_hash    VARCHAR(71) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_location_runtime_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_mine_location_runtime_snapshot_revision
        UNIQUE (site_id, revision),
    CONSTRAINT fk_mine_location_runtime_snapshot_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT ck_mine_location_runtime_snapshot_revision
        CHECK (revision > 0),
    CONSTRAINT ck_mine_location_runtime_snapshot_reason
        CHECK (change_reason IN (
            'SITE_CHANGE', 'ASSET_CHANGE', 'COORDINATE_CHANGE', 'BINDING_SWITCH'
        )),
    CONSTRAINT ck_mine_location_runtime_snapshot_count
        CHECK (item_count > 0),
    CONSTRAINT ck_mine_location_runtime_snapshot_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_mine_location_runtime_snapshot_site_revision
    ON mine_location_runtime_snapshot(site_id, revision DESC, id);

CREATE TABLE mine_location_runtime_snapshot_item (
    snapshot_id  UUID         NOT NULL,
    item_key     VARCHAR(160) NOT NULL,
    item_type    VARCHAR(48)  NOT NULL,
    content      JSONB        NOT NULL,
    content_hash VARCHAR(71)  NOT NULL,
    CONSTRAINT pk_mine_location_runtime_snapshot_item
        PRIMARY KEY (snapshot_id, item_key),
    CONSTRAINT fk_mine_location_runtime_snapshot_item_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES mine_location_runtime_snapshot(id),
    CONSTRAINT ck_mine_location_runtime_snapshot_item_key
        CHECK (item_key ~ '^[A-Z][A-Z_]*:[0-9a-f-]{36}$'),
    CONSTRAINT ck_mine_location_runtime_snapshot_item_type
        CHECK (item_type IN (
            'SITE', 'VEHICLE', 'PERSON', 'LOCATION_TAG', 'SOURCE_DEVICE',
            'COORDINATE_SYSTEM', 'COORDINATE_TRANSFORM',
            'VEHICLE_DEVICE_BINDING', 'VEHICLE_TAG_BINDING',
            'PERSON_TAG_BINDING', 'PERSON_VEHICLE_BINDING'
        )),
    CONSTRAINT ck_mine_location_runtime_snapshot_item_content
        CHECK (jsonb_typeof(content) = 'object'),
    CONSTRAINT ck_mine_location_runtime_snapshot_item_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_mine_location_runtime_snapshot_item_type
    ON mine_location_runtime_snapshot_item(snapshot_id, item_type, item_key);

CREATE TRIGGER trg_mine_location_runtime_snapshot_immutable
BEFORE UPDATE OR DELETE ON mine_location_runtime_snapshot
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

CREATE TRIGGER trg_mine_location_runtime_snapshot_item_immutable
BEFORE UPDATE OR DELETE ON mine_location_runtime_snapshot_item
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

COMMENT ON TABLE mine_location_runtime_snapshot IS '定位运行服务按矿区拉取的不可变完整快照';
COMMENT ON COLUMN mine_location_runtime_snapshot.id IS '定位运行快照 UUIDv7 标识';
COMMENT ON COLUMN mine_location_runtime_snapshot.organization_id IS '快照所属组织唯一标识';
COMMENT ON COLUMN mine_location_runtime_snapshot.site_id IS '快照所属矿区唯一标识';
COMMENT ON COLUMN mine_location_runtime_snapshot.revision IS '矿区内单调递增的运行修订号';
COMMENT ON COLUMN mine_location_runtime_snapshot.change_reason IS '触发快照的稳定业务变更原因';
COMMENT ON COLUMN mine_location_runtime_snapshot.item_count IS '快照包含的规范化条目数量';
COMMENT ON COLUMN mine_location_runtime_snapshot.content_hash IS '全部条目稳定排序后的 SHA-256 摘要';
COMMENT ON COLUMN mine_location_runtime_snapshot.created_at IS '快照在控制面的创建时间';

COMMENT ON TABLE mine_location_runtime_snapshot_item IS '定位运行快照中的单个非敏感规范化条目';
COMMENT ON COLUMN mine_location_runtime_snapshot_item.snapshot_id IS '所属定位运行快照标识';
COMMENT ON COLUMN mine_location_runtime_snapshot_item.item_key IS '按类型和对象标识组成的稳定分页键';
COMMENT ON COLUMN mine_location_runtime_snapshot_item.item_type IS '定位热路径条目的稳定类型';
COMMENT ON COLUMN mine_location_runtime_snapshot_item.content IS '不含人员敏感信息的规范化 JSON 内容';
COMMENT ON COLUMN mine_location_runtime_snapshot_item.content_hash IS '单条规范化内容的 SHA-256 摘要';
