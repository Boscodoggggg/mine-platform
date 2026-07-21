-- 设备接入配置只保存网络端点和诊断摘要；设备密钥继续由凭据表保存不可逆摘要。
CREATE TABLE dev_device_connection_profile (
    id                         UUID         NOT NULL,
    device_id                  UUID         NOT NULL,
    connection_mode            VARCHAR(48)  NOT NULL,
    host                       VARCHAR(253) NOT NULL,
    port                       INTEGER      NOT NULL,
    tls_enabled                BOOLEAN      NOT NULL DEFAULT FALSE,
    tls_server_name            VARCHAR(253),
    connect_timeout_millis     INTEGER      NOT NULL DEFAULT 5000,
    keep_alive_seconds         INTEGER      NOT NULL DEFAULT 60,
    offline_threshold_seconds  INTEGER      NOT NULL DEFAULT 180,
    last_test_status           VARCHAR(32)  NOT NULL DEFAULT 'NOT_TESTED',
    last_test_at               TIMESTAMPTZ,
    last_test_duration_millis  BIGINT,
    last_test_message          VARCHAR(500),
    last_test_stages           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                 UUID,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                 UUID,
    version                    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_device_connection_profile PRIMARY KEY (id),
    CONSTRAINT uk_dev_device_connection_profile_device UNIQUE (device_id),
    CONSTRAINT fk_dev_device_connection_profile_device
        FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT ck_dev_device_connection_profile_mode CHECK (
        connection_mode IN (
            'DEVICE_TO_PLATFORM_MQTT',
            'DEVICE_TO_PLATFORM_TCP',
            'PLATFORM_TO_DEVICE_TCP'
        )
    ),
    CONSTRAINT ck_dev_device_connection_profile_host CHECK (btrim(host) <> ''),
    CONSTRAINT ck_dev_device_connection_profile_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_dev_device_connection_profile_timeout CHECK (
        connect_timeout_millis BETWEEN 250 AND 30000
    ),
    CONSTRAINT ck_dev_device_connection_profile_keep_alive CHECK (
        keep_alive_seconds BETWEEN 0 AND 86400
    ),
    CONSTRAINT ck_dev_device_connection_profile_offline CHECK (
        offline_threshold_seconds BETWEEN 1 AND 604800
    ),
    CONSTRAINT ck_dev_device_connection_profile_test_status CHECK (
        last_test_status IN ('NOT_TESTED', 'PASSED', 'PARTIAL', 'FAILED')
    ),
    CONSTRAINT ck_dev_device_connection_profile_test_duration CHECK (
        last_test_duration_millis IS NULL OR last_test_duration_millis >= 0
    ),
    CONSTRAINT ck_dev_device_connection_profile_version CHECK (version >= 0)
);

CREATE INDEX idx_dev_device_connection_profile_test
    ON dev_device_connection_profile (last_test_status, last_test_at DESC);

COMMENT ON TABLE dev_device_connection_profile IS '设备网络接入配置与最近一次连通性诊断摘要';
COMMENT ON COLUMN dev_device_connection_profile.id IS '接入配置 UUIDv7 标识';
COMMENT ON COLUMN dev_device_connection_profile.device_id IS '所属设备标识，每台设备最多一份接入配置';
COMMENT ON COLUMN dev_device_connection_profile.connection_mode IS '设备主动 MQTT、设备主动 TCP 或平台主动 TCP 接入方式';
COMMENT ON COLUMN dev_device_connection_profile.host IS '平台接入端点或目标设备的 DNS 名称或 IP 地址';
COMMENT ON COLUMN dev_device_connection_profile.port IS '平台接入端口或目标设备端口';
COMMENT ON COLUMN dev_device_connection_profile.tls_enabled IS '是否执行 TLS 握手和证书校验';
COMMENT ON COLUMN dev_device_connection_profile.tls_server_name IS 'TLS SNI 与主机名校验名称，为空时使用 host';
COMMENT ON COLUMN dev_device_connection_profile.connect_timeout_millis IS 'DNS 之后 TCP/TLS 单阶段连接超时毫秒数';
COMMENT ON COLUMN dev_device_connection_profile.keep_alive_seconds IS '设备协议建议的保活间隔秒数，零表示不配置';
COMMENT ON COLUMN dev_device_connection_profile.offline_threshold_seconds IS '超过该秒数未收到设备数据时判定离线';
COMMENT ON COLUMN dev_device_connection_profile.last_test_status IS '最近诊断未测试、通过、部分通过或失败状态';
COMMENT ON COLUMN dev_device_connection_profile.last_test_at IS '最近一次诊断开始时间';
COMMENT ON COLUMN dev_device_connection_profile.last_test_duration_millis IS '最近一次诊断总耗时毫秒数';
COMMENT ON COLUMN dev_device_connection_profile.last_test_message IS '最近一次诊断面向接入工程师的脱敏结论';
COMMENT ON COLUMN dev_device_connection_profile.last_test_stages IS '最近一次配置、DNS、TCP 和 TLS 分层诊断结果';
COMMENT ON COLUMN dev_device_connection_profile.created_at IS '创建时间';
COMMENT ON COLUMN dev_device_connection_profile.created_by IS '创建人标识';
COMMENT ON COLUMN dev_device_connection_profile.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_device_connection_profile.updated_by IS '最后修改人标识';
COMMENT ON COLUMN dev_device_connection_profile.version IS '乐观锁版本号';

-- 接入配置和诊断属于设备台账按钮能力，分别授权查看、修改和执行测试。
INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000227', '019b0000-0000-7000-8000-000000000220',
     '查看设备接入配置', 'BUTTON', 'device:connection:view', 70, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000228', '019b0000-0000-7000-8000-000000000220',
     '修改设备接入配置', 'BUTTON', 'device:connection:update', 80, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000229', '019b0000-0000-7000-8000-000000000220',
     '测试设备接入端点', 'BUTTON', 'device:connection:test', 90, FALSE, TRUE);
