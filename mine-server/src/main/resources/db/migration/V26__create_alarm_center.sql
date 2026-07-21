-- 告警中心以告警周期为聚合根，将触发、恢复、确认和关闭保存为互不覆盖的业务事实。
CREATE TABLE alarm_event (
    id                      UUID          NOT NULL,
    device_id               UUID          NOT NULL,
    product_key             VARCHAR(64)   NOT NULL,
    rule_key                VARCHAR(64)   NOT NULL,
    rule_name               VARCHAR(100)  NOT NULL,
    source_type             VARCHAR(16)   NOT NULL,
    severity                VARCHAR(16)   NOT NULL,
    property_id             VARCHAR(128),
    policy_runtime_revision BIGINT        NOT NULL,
    policy_snapshot_id      UUID          NOT NULL,
    policy_content_hash     VARCHAR(71)   NOT NULL,
    raised_at               TIMESTAMPTZ   NOT NULL,
    raised_evidence         JSONB,
    raised_event_id         UUID,
    recovered_at            TIMESTAMPTZ,
    recovered_evidence      JSONB,
    recovered_event_id      UUID,
    recovery_reason         VARCHAR(32),
    acknowledged_at         TIMESTAMPTZ,
    acknowledged_by         UUID,
    acknowledgement_note    VARCHAR(1000),
    closed_at               TIMESTAMPTZ,
    closed_by               UUID,
    close_note              VARCHAR(1000),
    close_idempotency_key   VARCHAR(128),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_alarm_event PRIMARY KEY (id),
    CONSTRAINT fk_alarm_event_device FOREIGN KEY (device_id) REFERENCES dev_device (id),
    CONSTRAINT fk_alarm_event_acknowledged_by FOREIGN KEY (acknowledged_by) REFERENCES sys_user (id),
    CONSTRAINT fk_alarm_event_closed_by FOREIGN KEY (closed_by) REFERENCES sys_user (id),
    CONSTRAINT ck_alarm_event_identity CHECK (
        btrim(product_key) <> '' AND btrim(rule_key) <> '' AND btrim(rule_name) <> ''
    ),
    CONSTRAINT ck_alarm_event_source CHECK (source_type IN ('NUMERIC', 'QUALITY')),
    CONSTRAINT ck_alarm_event_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_alarm_event_property CHECK (
        (source_type = 'NUMERIC' AND property_id IS NOT NULL AND btrim(property_id) <> '')
        OR (source_type = 'QUALITY' AND (property_id IS NULL OR btrim(property_id) <> ''))
    ),
    CONSTRAINT ck_alarm_event_policy CHECK (
        policy_runtime_revision >= 1
        AND policy_content_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_alarm_event_raised_evidence CHECK (
        (raised_event_id IS NULL AND raised_evidence IS NULL)
        OR (raised_event_id IS NOT NULL
            AND raised_evidence IS NOT NULL
            AND jsonb_typeof(raised_evidence) = 'object')
    ),
    CONSTRAINT ck_alarm_event_recovery CHECK (
        (recovered_at IS NULL
            AND recovered_evidence IS NULL
            AND recovered_event_id IS NULL
            AND recovery_reason IS NULL)
        OR (recovered_at IS NOT NULL
            AND recovered_at >= raised_at
            AND recovered_evidence IS NOT NULL
            AND jsonb_typeof(recovered_evidence) = 'object'
            AND recovered_event_id IS NOT NULL
            AND recovery_reason IN ('CONDITION_CLEARED', 'RULE_DISABLED', 'RULE_REMOVED', 'RULE_CHANGED'))
    ),
    CONSTRAINT ck_alarm_event_acknowledgement CHECK (
        (acknowledged_at IS NULL AND acknowledged_by IS NULL AND acknowledgement_note IS NULL)
        OR (acknowledged_at IS NOT NULL
            AND acknowledged_by IS NOT NULL
            AND acknowledgement_note IS NOT NULL
            AND btrim(acknowledgement_note) <> '')
    ),
    CONSTRAINT ck_alarm_event_close CHECK (
        (closed_at IS NULL AND closed_by IS NULL AND close_note IS NULL AND close_idempotency_key IS NULL)
        OR (closed_at IS NOT NULL
            AND recovered_at IS NOT NULL
            AND closed_at >= recovered_at
            AND closed_by IS NOT NULL
            AND close_note IS NOT NULL
            AND btrim(close_note) <> ''
            AND close_idempotency_key IS NOT NULL
            AND btrim(close_idempotency_key) <> '')
    ),
    CONSTRAINT ck_alarm_event_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_alarm_event_raised_event
    ON alarm_event (raised_event_id)
    WHERE raised_event_id IS NOT NULL;

CREATE UNIQUE INDEX uk_alarm_event_recovered_event
    ON alarm_event (recovered_event_id)
    WHERE recovered_event_id IS NOT NULL;

CREATE INDEX idx_alarm_event_status_time
    ON alarm_event (closed_at, recovered_at, acknowledged_at, raised_at DESC, id DESC);

CREATE INDEX idx_alarm_event_device_time
    ON alarm_event (device_id, raised_at DESC, id DESC);

CREATE INDEX idx_alarm_event_rule_time
    ON alarm_event (rule_key, raised_at DESC, id DESC);

CREATE TABLE alarm_acknowledgement (
    id               UUID          NOT NULL,
    alarm_id         UUID          NOT NULL,
    operator_id      UUID          NOT NULL,
    note             VARCHAR(1000) NOT NULL,
    idempotency_key  VARCHAR(128)  NOT NULL,
    acknowledged_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_alarm_acknowledgement PRIMARY KEY (id),
    CONSTRAINT uk_alarm_acknowledgement_alarm UNIQUE (alarm_id),
    CONSTRAINT uk_alarm_acknowledgement_idempotency UNIQUE (alarm_id, idempotency_key),
    CONSTRAINT fk_alarm_acknowledgement_alarm FOREIGN KEY (alarm_id) REFERENCES alarm_event (id),
    CONSTRAINT fk_alarm_acknowledgement_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id),
    CONSTRAINT ck_alarm_acknowledgement_note CHECK (btrim(note) <> ''),
    CONSTRAINT ck_alarm_acknowledgement_idempotency CHECK (btrim(idempotency_key) <> '')
);

COMMENT ON TABLE alarm_event IS '告警周期及其触发恢复处置事实';
COMMENT ON COLUMN alarm_event.id IS '告警周期 UUIDv7 标识';
COMMENT ON COLUMN alarm_event.device_id IS '告警设备标识';
COMMENT ON COLUMN alarm_event.product_key IS '触发时稳定产品编码快照';
COMMENT ON COLUMN alarm_event.rule_key IS '稳定告警规则编码';
COMMENT ON COLUMN alarm_event.rule_name IS '触发时规则展示名称快照';
COMMENT ON COLUMN alarm_event.source_type IS '告警来源类型';
COMMENT ON COLUMN alarm_event.severity IS '告警等级';
COMMENT ON COLUMN alarm_event.property_id IS '数值告警属性编码，质量告警可按属性配置或为空';
COMMENT ON COLUMN alarm_event.policy_runtime_revision IS '策略运行修订号';
COMMENT ON COLUMN alarm_event.policy_snapshot_id IS '不可变策略快照标识';
COMMENT ON COLUMN alarm_event.policy_content_hash IS '单设备策略内容摘要';
COMMENT ON COLUMN alarm_event.raised_at IS '告警触发时间';
COMMENT ON COLUMN alarm_event.raised_evidence IS '触发时有界结构化证据';
COMMENT ON COLUMN alarm_event.raised_event_id IS '触发消息事件标识';
COMMENT ON COLUMN alarm_event.recovered_at IS '告警恢复时间';
COMMENT ON COLUMN alarm_event.recovered_evidence IS '恢复时有界结构化证据';
COMMENT ON COLUMN alarm_event.recovered_event_id IS '恢复消息事件标识';
COMMENT ON COLUMN alarm_event.recovery_reason IS '恢复原因';
COMMENT ON COLUMN alarm_event.acknowledged_at IS '首次确认时间';
COMMENT ON COLUMN alarm_event.acknowledged_by IS '首次确认操作人标识';
COMMENT ON COLUMN alarm_event.acknowledgement_note IS '首次确认处置说明';
COMMENT ON COLUMN alarm_event.closed_at IS '关闭时间';
COMMENT ON COLUMN alarm_event.closed_by IS '关闭操作人标识';
COMMENT ON COLUMN alarm_event.close_note IS '关闭说明';
COMMENT ON COLUMN alarm_event.close_idempotency_key IS '关闭请求幂等键';
COMMENT ON COLUMN alarm_event.created_at IS '记录创建时间';
COMMENT ON COLUMN alarm_event.updated_at IS '最后更新时间';
COMMENT ON COLUMN alarm_event.version IS '乐观锁版本';

COMMENT ON TABLE alarm_acknowledgement IS '告警首次确认事实';
COMMENT ON COLUMN alarm_acknowledgement.id IS '确认事实 UUIDv7 标识';
COMMENT ON COLUMN alarm_acknowledgement.alarm_id IS '所属告警周期标识';
COMMENT ON COLUMN alarm_acknowledgement.operator_id IS '确认操作人标识';
COMMENT ON COLUMN alarm_acknowledgement.note IS '确认处置说明';
COMMENT ON COLUMN alarm_acknowledgement.idempotency_key IS '确认请求幂等键';
COMMENT ON COLUMN alarm_acknowledgement.acknowledged_at IS '确认时间';

-- 发布告警中心和独立处置权限；普通角色仍由管理员按职责授权。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000400', NULL,
     '告警管理', 'DIRECTORY', '/alarm', NULL, NULL,
     'mdi:alarm-light-outline', 150, TRUE, 'mine.menu.alarms', TRUE),
    ('019b0000-0000-7000-8000-000000000410', '019b0000-0000-7000-8000-000000000400',
     '告警中心', 'MENU', '/alarm/center', 'alarm-center', 'alarm:event:view',
     'mdi:alarm-panel-outline', 10, TRUE, 'mine.menu.alarmCenter', TRUE),
    ('019b0000-0000-7000-8000-000000000411', '019b0000-0000-7000-8000-000000000410',
     '确认告警', 'BUTTON', NULL, NULL, 'alarm:event:ack',
     NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000412', '019b0000-0000-7000-8000-000000000410',
     '关闭告警', 'BUTTON', NULL, NULL, 'alarm:event:close',
     NULL, 20, FALSE, NULL, TRUE);
