-- 命令实例保留每次下行意图、幂等键和状态时间线；发布消息由 sys_outbox_event 保证。
CREATE TABLE cmd_instance (
    id                 UUID          NOT NULL,
    device_id          UUID          NOT NULL,
    template_key       VARCHAR(128)  NOT NULL,
    template_version   INTEGER       NOT NULL,
    parameters         JSONB         NOT NULL DEFAULT '{}'::JSONB,
    reason             VARCHAR(1000),
    risk_level         VARCHAR(16)   NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    ttl_seconds        INTEGER       NOT NULL,
    timeout_seconds    INTEGER       NOT NULL,
    retry_policy       JSONB         NOT NULL DEFAULT '{"maxAttempts":0}'::JSONB,
    idempotency_key    VARCHAR(128)  NOT NULL,
    protocol_version   VARCHAR(128),
    config_release_id  UUID,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    queued_at          TIMESTAMPTZ,
    sent_at            TIMESTAMPTZ,
    device_received_at TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ   NOT NULL,
    last_error_code    VARCHAR(64),
    reply              JSONB,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_cmd_instance PRIMARY KEY (id),
    CONSTRAINT uk_cmd_instance_creator_idempotency UNIQUE (created_by, idempotency_key),
    CONSTRAINT fk_cmd_instance_device FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT fk_cmd_instance_creator FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT ck_cmd_instance_template_key CHECK (btrim(template_key) <> ''),
    CONSTRAINT ck_cmd_instance_template_version CHECK (template_version > 0),
    CONSTRAINT ck_cmd_instance_parameters CHECK (jsonb_typeof(parameters) = 'object'),
    CONSTRAINT ck_cmd_instance_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT ck_cmd_instance_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_cmd_instance_status CHECK (
        status IN (
            'CREATED', 'QUEUED', 'SENT', 'DEVICE_RECEIVED',
            'SUCCEEDED', 'REFUSED', 'FAILED', 'EXPIRED', 'TIMEOUT', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_cmd_instance_ttl CHECK (ttl_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_cmd_instance_timeout CHECK (timeout_seconds BETWEEN 1 AND ttl_seconds),
    CONSTRAINT ck_cmd_instance_retry_policy CHECK (jsonb_typeof(retry_policy) = 'object'),
    CONSTRAINT ck_cmd_instance_idempotency CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_cmd_instance_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_cmd_instance_reply CHECK (reply IS NULL OR jsonb_typeof(reply) = 'object'),
    CONSTRAINT ck_cmd_instance_version CHECK (version >= 0)
);

CREATE INDEX idx_cmd_instance_device_time
    ON cmd_instance (device_id, created_at DESC, id);

CREATE INDEX idx_cmd_instance_status_expiry
    ON cmd_instance (status, expires_at, id)
    WHERE status IN ('QUEUED', 'SENT', 'DEVICE_RECEIVED');

COMMENT ON TABLE cmd_instance IS '设备下行命令实例及状态时间线';
COMMENT ON COLUMN cmd_instance.id IS '命令 UUIDv7 标识';
COMMENT ON COLUMN cmd_instance.device_id IS '目标设备标识';
COMMENT ON COLUMN cmd_instance.template_key IS '稳定命令模板编码';
COMMENT ON COLUMN cmd_instance.template_version IS '提交时使用的命令模板版本';
COMMENT ON COLUMN cmd_instance.parameters IS '经模板 Schema 校验后的命令参数';
COMMENT ON COLUMN cmd_instance.reason IS '管理员提交命令的原因';
COMMENT ON COLUMN cmd_instance.risk_level IS '提交时固化的风险级别';
COMMENT ON COLUMN cmd_instance.status IS '命令状态机当前状态';
COMMENT ON COLUMN cmd_instance.ttl_seconds IS '命令允许在队列中存活的秒数';
COMMENT ON COLUMN cmd_instance.timeout_seconds IS '命令发送后等待业务回复的秒数';
COMMENT ON COLUMN cmd_instance.retry_policy IS '提交时固化的有界重试策略';
COMMENT ON COLUMN cmd_instance.idempotency_key IS '同一创建人提交命令的幂等键';
COMMENT ON COLUMN cmd_instance.protocol_version IS '编码时预期使用的协议版本';
COMMENT ON COLUMN cmd_instance.config_release_id IS '编码时预期使用的配置包发布标识';
COMMENT ON COLUMN cmd_instance.created_by IS '命令创建人标识';
COMMENT ON COLUMN cmd_instance.created_at IS '命令创建时间';
COMMENT ON COLUMN cmd_instance.updated_at IS '命令最后状态变更时间';
COMMENT ON COLUMN cmd_instance.queued_at IS '命令进入可发布队列的时间';
COMMENT ON COLUMN cmd_instance.sent_at IS 'Broker 或连接层确认发送的时间';
COMMENT ON COLUMN cmd_instance.device_received_at IS '设备应用层确认收到命令的时间';
COMMENT ON COLUMN cmd_instance.completed_at IS '命令进入终态的时间';
COMMENT ON COLUMN cmd_instance.expires_at IS '命令 TTL 绝对截止时间';
COMMENT ON COLUMN cmd_instance.last_error_code IS '最后一次失败或拒绝错误码';
COMMENT ON COLUMN cmd_instance.reply IS '设备业务回复的脱敏结构';
COMMENT ON COLUMN cmd_instance.version IS '命令状态乐观锁版本';
