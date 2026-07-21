-- 消费幂等记录与遥测历史同库事务提交，避免消息重投产生重复历史副作用。
CREATE TABLE telemetry_inbox (
    event_id        UUID         NOT NULL,
    device_id       UUID         NOT NULL,
    message_type    VARCHAR(64)  NOT NULL,
    boot_id         VARCHAR(64),
    sequence        BIGINT,
    received_at     TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_inbox PRIMARY KEY (event_id),
    CONSTRAINT ck_ti_message_type CHECK (btrim(message_type) <> ''),
    CONSTRAINT ck_ti_boot_id CHECK (boot_id IS NULL OR btrim(boot_id) <> ''),
    CONSTRAINT ck_ti_sequence CHECK (sequence IS NULL OR sequence >= 0)
);

CREATE UNIQUE INDEX uq_ti_business_key
    ON telemetry_inbox (device_id, boot_id, sequence, message_type)
    WHERE boot_id IS NOT NULL AND sequence IS NOT NULL;

CREATE INDEX idx_ti_processed_at
    ON telemetry_inbox (processed_at DESC);

COMMENT ON TABLE telemetry_inbox IS '遥测消息消费幂等记录';
COMMENT ON COLUMN telemetry_inbox.event_id IS '平台内部事件唯一标识';
COMMENT ON COLUMN telemetry_inbox.device_id IS '平台设备唯一标识';
COMMENT ON COLUMN telemetry_inbox.message_type IS '统一设备消息类型';
COMMENT ON COLUMN telemetry_inbox.boot_id IS '设备本次启动标识，可为空';
COMMENT ON COLUMN telemetry_inbox.sequence IS '设备启动周期内消息序号，可为空';
COMMENT ON COLUMN telemetry_inbox.received_at IS '平台接收消息时间（UTC）';
COMMENT ON COLUMN telemetry_inbox.processed_at IS '遥测服务完成持久化时间（UTC）';
