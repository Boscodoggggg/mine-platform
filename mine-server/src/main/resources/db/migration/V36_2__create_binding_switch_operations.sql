-- 持久化换绑幂等请求和提交结果，防止 HTTP 重试重复改写时间关系。
CREATE TABLE mine_binding_switch_operation (
    id                    UUID         NOT NULL,
    organization_id       UUID         NOT NULL,
    site_id               UUID         NOT NULL,
    operator_id           UUID         NOT NULL,
    idempotency_key       VARCHAR(128) NOT NULL,
    request_hash          VARCHAR(71)  NOT NULL,
    binding_type          VARCHAR(32)  NOT NULL,
    resource_id           UUID         NOT NULL,
    target_id             UUID         NOT NULL,
    binding_role          VARCHAR(24),
    effective_at          TIMESTAMPTZ  NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    previous_binding_type VARCHAR(32),
    previous_binding_id   UUID,
    new_binding_id        UUID         NOT NULL,
    qualification_id      UUID,
    outbox_event_id       UUID         NOT NULL,
    response_payload      JSONB        NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_binding_switch_operation PRIMARY KEY (id),
    CONSTRAINT uk_mine_binding_switch_operation_idempotency
        UNIQUE (operator_id, idempotency_key),
    CONSTRAINT fk_mine_binding_switch_operation_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_binding_switch_operation_operator
        FOREIGN KEY (operator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_binding_switch_operation_key
        CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_mine_binding_switch_operation_hash
        CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mine_binding_switch_operation_type
        CHECK (binding_type IN (
            'VEHICLE_DEVICE', 'VEHICLE_TAG', 'PERSON_TAG', 'PERSON_VEHICLE'
        )),
    CONSTRAINT ck_mine_binding_switch_operation_role CHECK (
        (binding_type = 'VEHICLE_DEVICE' AND binding_role IN ('PRIMARY', 'SECONDARY'))
        OR (binding_type = 'PERSON_VEHICLE'
            AND binding_role IN ('PRIMARY_DRIVER', 'RELIEF_DRIVER'))
        OR (binding_type IN ('VEHICLE_TAG', 'PERSON_TAG') AND binding_role IS NULL)
    ),
    CONSTRAINT ck_mine_binding_switch_operation_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_binding_switch_operation_response
        CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_mine_binding_switch_operation_previous CHECK (
        (previous_binding_type IS NULL AND previous_binding_id IS NULL)
        OR (
            previous_binding_type IN (
                'VEHICLE_DEVICE', 'VEHICLE_TAG', 'PERSON_TAG', 'PERSON_VEHICLE'
            )
            AND previous_binding_id IS NOT NULL
        )
    )
);

CREATE INDEX idx_mine_binding_switch_operation_site_time
    ON mine_binding_switch_operation(site_id, created_at DESC, id);
CREATE INDEX idx_mine_binding_switch_operation_resource_time
    ON mine_binding_switch_operation(resource_id, effective_at DESC, id);

COMMENT ON TABLE mine_binding_switch_operation IS '矿区资产换绑的管理员幂等请求与原子提交结果';
COMMENT ON COLUMN mine_binding_switch_operation.id IS '换绑操作唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.operator_id IS '执行换绑的管理员唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.idempotency_key IS '管理员提交的换绑幂等键';
COMMENT ON COLUMN mine_binding_switch_operation.request_hash IS '规范化换绑请求的 SHA-256 摘要';
COMMENT ON COLUMN mine_binding_switch_operation.binding_type IS '拟建立的车辆设备、车辆标签、人员标签或人员车辆关系类型';
COMMENT ON COLUMN mine_binding_switch_operation.resource_id IS '被移动的设备、标签或人员唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.target_id IS '拟绑定的车辆或人员唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.binding_role IS '主辅设备或主替班司机角色，标签关系为空';
COMMENT ON COLUMN mine_binding_switch_operation.effective_at IS '换绑生效时刻，包含该时刻';
COMMENT ON COLUMN mine_binding_switch_operation.reason IS '管理员填写的换绑业务原因';
COMMENT ON COLUMN mine_binding_switch_operation.previous_binding_type IS '生效时刻被关闭的旧关系类型，无旧关系时为空';
COMMENT ON COLUMN mine_binding_switch_operation.previous_binding_id IS '生效时刻被关闭的旧关系唯一标识，无旧关系时为空';
COMMENT ON COLUMN mine_binding_switch_operation.new_binding_id IS '本次建立的新关系唯一标识';
COMMENT ON COLUMN mine_binding_switch_operation.qualification_id IS '司机换车时命中的有效车型资质标识';
COMMENT ON COLUMN mine_binding_switch_operation.outbox_event_id IS '同事务写入的业务 Outbox 事件标识';
COMMENT ON COLUMN mine_binding_switch_operation.response_payload IS '首次提交返回的不可变完整响应快照';
COMMENT ON COLUMN mine_binding_switch_operation.created_at IS '换绑操作提交时间';
