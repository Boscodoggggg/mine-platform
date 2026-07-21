-- 运行策略快照把控制面的有效策略增量可靠交付给数据面，空对象条目表示删除本地策略。
CREATE TABLE dev_strategy_runtime_snapshot (
    id                UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    strategy_type     VARCHAR(64) NOT NULL,
    runtime_revision  BIGINT      NOT NULL,
    change_reason     VARCHAR(32) NOT NULL,
    item_count        INTEGER     NOT NULL,
    content_hash      VARCHAR(71) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dev_strategy_runtime_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_dev_strategy_runtime_snapshot_revision
        UNIQUE (product_id, strategy_type, runtime_revision),
    CONSTRAINT fk_dev_strategy_runtime_snapshot_product
        FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_type
        CHECK (strategy_type IN ('DATA_QUALITY')),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_revision CHECK (runtime_revision > 0),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_reason CHECK (
        change_reason IN (
            'PUBLISH', 'ROLLBACK', 'MEMBERSHIP', 'PRIORITY', 'GROUP_ARCHIVE',
            'DEVICE_CREATE', 'DEVICE_ARCHIVE'
        )
    ),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_count CHECK (item_count >= 0),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_dev_strategy_runtime_snapshot_product_type_created
    ON dev_strategy_runtime_snapshot (product_id, strategy_type, created_at DESC);

CREATE TABLE dev_strategy_runtime_snapshot_item (
    snapshot_id   UUID        NOT NULL,
    device_id     UUID        NOT NULL,
    values_json   JSONB       NOT NULL,
    content_hash  VARCHAR(71) NOT NULL,
    CONSTRAINT pk_dev_strategy_runtime_snapshot_item PRIMARY KEY (snapshot_id, device_id),
    CONSTRAINT fk_dev_strategy_runtime_snapshot_item_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES dev_strategy_runtime_snapshot (id),
    CONSTRAINT fk_dev_strategy_runtime_snapshot_item_device
        FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_item_values
        CHECK (jsonb_typeof(values_json) = 'object'),
    CONSTRAINT ck_dev_strategy_runtime_snapshot_item_hash
        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_dev_strategy_runtime_snapshot_item_device
    ON dev_strategy_runtime_snapshot_item (device_id, snapshot_id);

CREATE TRIGGER trg_dev_strategy_runtime_snapshot_immutable
BEFORE UPDATE OR DELETE ON dev_strategy_runtime_snapshot
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

CREATE TRIGGER trg_dev_strategy_runtime_snapshot_item_immutable
BEFORE UPDATE OR DELETE ON dev_strategy_runtime_snapshot_item
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

COMMENT ON TABLE dev_strategy_runtime_snapshot IS '控制面交付给运行服务的不可变有效策略增量快照';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.id IS '策略运行快照 UUIDv7 标识';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.product_id IS '快照所属设备产品标识';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.strategy_type IS '快照承载的稳定策略类型';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.runtime_revision IS '产品和策略类型内单调递增的运行修订号';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.change_reason IS '触发增量快照的控制面变更原因';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.item_count IS '快照包含的设备策略条目数量';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.content_hash IS '稳定排序后完整增量快照的 SHA-256';
COMMENT ON COLUMN dev_strategy_runtime_snapshot.created_at IS '快照创建时间';

COMMENT ON TABLE dev_strategy_runtime_snapshot_item IS '不可变策略增量快照中的单设备最终有效值';
COMMENT ON COLUMN dev_strategy_runtime_snapshot_item.snapshot_id IS '所属策略运行快照标识';
COMMENT ON COLUMN dev_strategy_runtime_snapshot_item.device_id IS '策略生效或删除的设备标识';
COMMENT ON COLUMN dev_strategy_runtime_snapshot_item.values_json IS '规范化最终有效策略，空对象表示删除运行服务本地策略';
COMMENT ON COLUMN dev_strategy_runtime_snapshot_item.content_hash IS '单设备最终有效策略的 SHA-256';
