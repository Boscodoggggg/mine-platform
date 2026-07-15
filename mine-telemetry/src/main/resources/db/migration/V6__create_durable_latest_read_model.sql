-- Redis 只承担实时查询加速；该读模型持久保存经过顺序与时间策略筛选后的最新值及恢复状态。
CREATE TABLE telemetry_latest_state (
    device_id              UUID        NOT NULL,
    current_boot_id        VARCHAR(64),
    highest_sequence       BIGINT,
    latest_effective_at    TIMESTAMPTZ,
    latest_event_id        UUID,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_latest_state PRIMARY KEY (device_id),
    CONSTRAINT ck_tls_boot_sequence_pair CHECK (
        (current_boot_id IS NULL AND highest_sequence IS NULL)
        OR (current_boot_id IS NOT NULL AND highest_sequence IS NOT NULL AND highest_sequence >= 0)
    )
);

CREATE TABLE telemetry_retired_boot (
    device_id      UUID        NOT NULL,
    boot_id        VARCHAR(64) NOT NULL,
    retired_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_retired_boot PRIMARY KEY (device_id, boot_id),
    CONSTRAINT ck_trb_boot_id CHECK (btrim(boot_id) <> '')
);

CREATE TABLE telemetry_latest_numeric (
    device_id       UUID             NOT NULL,
    property_id     VARCHAR(128)     NOT NULL,
    value_double    DOUBLE PRECISION NOT NULL,
    occurred_at     TIMESTAMPTZ      NOT NULL,
    effective_at    TIMESTAMPTZ      NOT NULL,
    received_at     TIMESTAMPTZ      NOT NULL,
    quality         SMALLINT         NOT NULL,
    boot_id         VARCHAR(64),
    sequence        BIGINT,
    event_id        UUID             NOT NULL,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_latest_numeric PRIMARY KEY (device_id, property_id),
    CONSTRAINT ck_tln_property_id CHECK (btrim(property_id) <> ''),
    CONSTRAINT ck_tln_quality CHECK (quality BETWEEN 0 AND 3),
    CONSTRAINT ck_tln_sequence CHECK (sequence IS NULL OR sequence >= 0)
);

COMMENT ON TABLE telemetry_latest_state IS '设备最新值顺序判定的持久化状态';
COMMENT ON COLUMN telemetry_latest_state.device_id IS '平台设备唯一标识';
COMMENT ON COLUMN telemetry_latest_state.current_boot_id IS '当前有效的设备启动周期标识';
COMMENT ON COLUMN telemetry_latest_state.highest_sequence IS '当前启动周期已处理的最高消息序号';
COMMENT ON COLUMN telemetry_latest_state.latest_effective_at IS '最近一次生效事件的有效排序时间（UTC）';
COMMENT ON COLUMN telemetry_latest_state.latest_event_id IS '最近一次生效事件的唯一标识';
COMMENT ON COLUMN telemetry_latest_state.updated_at IS '顺序状态最后更新时间（UTC）';

COMMENT ON TABLE telemetry_retired_boot IS '设备已退役启动周期的持久化集合';
COMMENT ON COLUMN telemetry_retired_boot.device_id IS '平台设备唯一标识';
COMMENT ON COLUMN telemetry_retired_boot.boot_id IS '已退役的设备启动周期标识';
COMMENT ON COLUMN telemetry_retired_boot.retired_at IS '启动周期退役时间（UTC）';

COMMENT ON TABLE telemetry_latest_numeric IS '经过顺序与时间策略筛选后的设备数值最新值';
COMMENT ON COLUMN telemetry_latest_numeric.device_id IS '平台设备唯一标识';
COMMENT ON COLUMN telemetry_latest_numeric.property_id IS '物模型属性标识';
COMMENT ON COLUMN telemetry_latest_numeric.value_double IS '数值型属性最新值';
COMMENT ON COLUMN telemetry_latest_numeric.occurred_at IS '设备声明的原始数据发生时间（UTC）';
COMMENT ON COLUMN telemetry_latest_numeric.effective_at IS '平台时间质量策略判定后的有效排序时间（UTC）';
COMMENT ON COLUMN telemetry_latest_numeric.received_at IS '平台接收时间（UTC）';
COMMENT ON COLUMN telemetry_latest_numeric.quality IS '数据质量编码';
COMMENT ON COLUMN telemetry_latest_numeric.boot_id IS '产生最新值的设备启动周期标识';
COMMENT ON COLUMN telemetry_latest_numeric.sequence IS '产生最新值的启动周期内消息序号';
COMMENT ON COLUMN telemetry_latest_numeric.event_id IS '产生最新值的统一消息事件唯一标识';
COMMENT ON COLUMN telemetry_latest_numeric.updated_at IS '最新值最后更新时间（UTC）';
