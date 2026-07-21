-- 设备组、历史成员关系和作用域策略是有效配置计算的控制面事实源。
CREATE TABLE dev_device_group (
    id           UUID         NOT NULL,
    product_id   UUID         NOT NULL,
    group_key    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    priority     INTEGER      NOT NULL DEFAULT 0,
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    description  VARCHAR(500),
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   UUID,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   UUID,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_device_group PRIMARY KEY (id),
    CONSTRAINT uk_dev_device_group_product_key UNIQUE (product_id, group_key),
    CONSTRAINT fk_dev_device_group_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_device_group_key CHECK (group_key ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_dev_device_group_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dev_device_group_priority CHECK (priority BETWEEN 0 AND 1000),
    CONSTRAINT ck_dev_device_group_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_dev_device_group_archive CHECK (
        (status = 'ARCHIVED' AND archived_at IS NOT NULL)
        OR (status = 'ACTIVE' AND archived_at IS NULL)
    ),
    CONSTRAINT ck_dev_device_group_version CHECK (version >= 0)
);

CREATE INDEX idx_dev_device_group_product_status_priority
    ON dev_device_group (product_id, status, priority DESC, name, id);

CREATE TABLE dev_device_group_membership (
    id              UUID        NOT NULL,
    group_id        UUID        NOT NULL,
    device_id       UUID        NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    ended_by        UUID,
    CONSTRAINT pk_dev_device_group_membership PRIMARY KEY (id),
    CONSTRAINT fk_dev_device_group_membership_group FOREIGN KEY (group_id)
        REFERENCES dev_device_group (id),
    CONSTRAINT fk_dev_device_group_membership_device FOREIGN KEY (device_id)
        REFERENCES dev_device (id),
    CONSTRAINT ck_dev_device_group_membership_time CHECK (
        effective_to IS NULL OR effective_to > effective_from
    )
);

CREATE UNIQUE INDEX uk_dev_device_group_membership_active
    ON dev_device_group_membership (group_id, device_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_dev_device_group_membership_device_active
    ON dev_device_group_membership (device_id, group_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_dev_device_group_membership_group_active
    ON dev_device_group_membership (group_id, device_id)
    WHERE effective_to IS NULL;

CREATE TABLE dev_strategy_version (
    id              UUID         NOT NULL,
    product_id      UUID         NOT NULL,
    strategy_type   VARCHAR(64)  NOT NULL,
    version_number  BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    values_json     JSONB        NOT NULL,
    content_hash    VARCHAR(71),
    description     VARCHAR(500),
    published_at    TIMESTAMPTZ,
    published_by    UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_strategy_version PRIMARY KEY (id),
    CONSTRAINT uk_dev_strategy_version_product_type_number
        UNIQUE (product_id, strategy_type, version_number),
    CONSTRAINT fk_dev_strategy_version_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT ck_dev_strategy_version_type CHECK (strategy_type IN ('ONLINE_STATUS')),
    CONSTRAINT ck_dev_strategy_version_number CHECK (version_number > 0),
    CONSTRAINT ck_dev_strategy_version_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_dev_strategy_version_values CHECK (
        jsonb_typeof(values_json) = 'object' AND values_json <> '{}'::JSONB
    ),
    CONSTRAINT ck_dev_strategy_version_hash CHECK (
        content_hash IS NULL OR content_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_dev_strategy_version_publish CHECK (
        (status = 'DRAFT' AND content_hash IS NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND content_hash IS NOT NULL AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_dev_strategy_version_lock CHECK (version >= 0)
);

CREATE INDEX idx_dev_strategy_version_product_type_status
    ON dev_strategy_version (product_id, strategy_type, status, version_number DESC);

CREATE TABLE dev_strategy_release (
    id                      UUID         NOT NULL,
    product_id              UUID         NOT NULL,
    strategy_version_id     UUID         NOT NULL,
    strategy_type           VARCHAR(64)  NOT NULL,
    release_sequence        BIGINT       NOT NULL,
    scope_type              VARCHAR(32)  NOT NULL,
    scope_id                UUID         NOT NULL,
    group_priority          INTEGER,
    action                  VARCHAR(32)  NOT NULL,
    rollback_of_release_id  UUID,
    reason                  VARCHAR(500) NOT NULL,
    idempotency_key         VARCHAR(128) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              UUID,
    CONSTRAINT pk_dev_strategy_release PRIMARY KEY (id),
    CONSTRAINT uk_dev_strategy_release_product_sequence UNIQUE (product_id, release_sequence),
    CONSTRAINT fk_dev_strategy_release_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT fk_dev_strategy_release_version FOREIGN KEY (strategy_version_id)
        REFERENCES dev_strategy_version (id),
    CONSTRAINT fk_dev_strategy_release_rollback FOREIGN KEY (rollback_of_release_id)
        REFERENCES dev_strategy_release (id),
    CONSTRAINT ck_dev_strategy_release_type CHECK (strategy_type IN ('ONLINE_STATUS')),
    CONSTRAINT ck_dev_strategy_release_sequence CHECK (release_sequence > 0),
    CONSTRAINT ck_dev_strategy_release_scope CHECK (scope_type IN ('PRODUCT', 'GROUP', 'DEVICE')),
    CONSTRAINT ck_dev_strategy_release_priority CHECK (
        (scope_type = 'GROUP' AND group_priority BETWEEN 0 AND 1000)
        OR (scope_type <> 'GROUP' AND group_priority IS NULL)
    ),
    CONSTRAINT ck_dev_strategy_release_action CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_dev_strategy_release_rollback CHECK (
        (action = 'PUBLISH' AND rollback_of_release_id IS NULL)
        OR (action = 'ROLLBACK' AND rollback_of_release_id IS NOT NULL)
    ),
    CONSTRAINT ck_dev_strategy_release_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_dev_strategy_release_idempotency CHECK (btrim(idempotency_key) <> '')
);

CREATE UNIQUE INDEX uk_dev_strategy_release_creator_idempotency
    ON dev_strategy_release (created_by, idempotency_key)
    WHERE created_by IS NOT NULL;

CREATE INDEX idx_dev_strategy_release_current_scope
    ON dev_strategy_release (
        product_id, strategy_type, scope_type, scope_id, release_sequence DESC
    );

-- 数据库再次校验设备与组属于同一产品，防止绕过服务层写入跨产品成员关系。
CREATE FUNCTION dev_validate_device_group_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM dev_device_group device_group
        JOIN dev_device device ON device.product_id = device_group.product_id
        WHERE device_group.id = NEW.group_id
          AND device.id = NEW.device_id
          AND device_group.status = 'ACTIVE'
          AND device.status <> 'ARCHIVED'
    ) THEN
        RAISE EXCEPTION '设备与设备组必须属于同一有效产品'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_dev_device_group_membership_validate
BEFORE INSERT OR UPDATE OF group_id, device_id, effective_to
ON dev_device_group_membership
FOR EACH ROW EXECUTE FUNCTION dev_validate_device_group_membership();

-- 发布只允许引用同产品、同类型的已发布版本，并校验多态作用范围。
CREATE FUNCTION dev_validate_strategy_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    version_product UUID;
    version_type VARCHAR(64);
    version_status VARCHAR(32);
    target_priority INTEGER;
BEGIN
    SELECT product_id, strategy_type, status
      INTO version_product, version_type, version_status
      FROM dev_strategy_version
     WHERE id = NEW.strategy_version_id;

    IF version_product IS NULL
       OR version_product <> NEW.product_id
       OR version_type <> NEW.strategy_type
       OR version_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION '策略发布必须引用同产品同类型的已发布版本'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.scope_type = 'PRODUCT' THEN
        IF NEW.scope_id <> NEW.product_id THEN
            RAISE EXCEPTION '产品策略作用范围必须指向所属产品'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.scope_type = 'GROUP' THEN
        SELECT priority
          INTO target_priority
          FROM dev_device_group
         WHERE id = NEW.scope_id
           AND product_id = NEW.product_id
           AND status = 'ACTIVE';
        IF target_priority IS NULL OR target_priority IS DISTINCT FROM NEW.group_priority THEN
            RAISE EXCEPTION '设备组策略作用范围或优先级快照不一致'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.scope_type = 'DEVICE' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM dev_device
             WHERE id = NEW.scope_id
               AND product_id = NEW.product_id
               AND status <> 'ARCHIVED'
        ) THEN
            RAISE EXCEPTION '单设备策略作用范围不属于发布产品'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_dev_strategy_release_validate
BEFORE INSERT ON dev_strategy_release
FOR EACH ROW EXECUTE FUNCTION dev_validate_strategy_release();

-- 草稿可以修改或发布，已发布版本和发布事实只允许新增。
CREATE FUNCTION dev_reject_published_strategy_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION '已发布策略版本不可修改或删除'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_dev_strategy_version_published_immutable
BEFORE UPDATE OR DELETE ON dev_strategy_version
FOR EACH ROW EXECUTE FUNCTION dev_reject_published_strategy_version_change();

CREATE TRIGGER trg_dev_strategy_release_immutable
BEFORE UPDATE OR DELETE ON dev_strategy_release
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

COMMENT ON TABLE dev_device_group IS '同一产品内用于继承策略的设备分组';
COMMENT ON COLUMN dev_device_group.id IS '设备组 UUIDv7 标识';
COMMENT ON COLUMN dev_device_group.product_id IS '设备组所属产品标识';
COMMENT ON COLUMN dev_device_group.group_key IS '产品内稳定唯一的设备组编码';
COMMENT ON COLUMN dev_device_group.name IS '设备组名称';
COMMENT ON COLUMN dev_device_group.priority IS '设备组策略覆盖优先级，数值越大优先级越高';
COMMENT ON COLUMN dev_device_group.status IS '设备组启用或归档状态';
COMMENT ON COLUMN dev_device_group.description IS '设备组用途说明';
COMMENT ON COLUMN dev_device_group.archived_at IS '设备组归档时间';
COMMENT ON COLUMN dev_device_group.created_at IS '创建时间';
COMMENT ON COLUMN dev_device_group.created_by IS '创建人标识';
COMMENT ON COLUMN dev_device_group.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_device_group.updated_by IS '最后修改人标识';
COMMENT ON COLUMN dev_device_group.version IS '乐观锁版本号';

COMMENT ON TABLE dev_device_group_membership IS '设备加入设备组的历史生效区间';
COMMENT ON COLUMN dev_device_group_membership.id IS '设备组成员关系 UUIDv7 标识';
COMMENT ON COLUMN dev_device_group_membership.group_id IS '所属设备组标识';
COMMENT ON COLUMN dev_device_group_membership.device_id IS '成员设备标识';
COMMENT ON COLUMN dev_device_group_membership.effective_from IS '成员关系开始生效时间';
COMMENT ON COLUMN dev_device_group_membership.effective_to IS '成员关系结束生效时间，当前关系为空';
COMMENT ON COLUMN dev_device_group_membership.created_at IS '成员关系创建时间';
COMMENT ON COLUMN dev_device_group_membership.created_by IS '成员关系创建人标识';
COMMENT ON COLUMN dev_device_group_membership.ended_by IS '成员关系结束操作人标识';

COMMENT ON TABLE dev_strategy_version IS '经过白名单校验的版本化局部策略定义';
COMMENT ON COLUMN dev_strategy_version.id IS '策略版本 UUIDv7 标识';
COMMENT ON COLUMN dev_strategy_version.product_id IS '策略版本所属产品标识';
COMMENT ON COLUMN dev_strategy_version.strategy_type IS '在线状态等稳定策略类型';
COMMENT ON COLUMN dev_strategy_version.version_number IS '产品和策略类型内单调版本号';
COMMENT ON COLUMN dev_strategy_version.status IS '草稿或已发布状态';
COMMENT ON COLUMN dev_strategy_version.values_json IS '经过类型注册表校验的局部策略字段';
COMMENT ON COLUMN dev_strategy_version.content_hash IS '已发布规范化策略字段的 SHA-256';
COMMENT ON COLUMN dev_strategy_version.description IS '策略版本用途说明';
COMMENT ON COLUMN dev_strategy_version.published_at IS '策略版本发布时间';
COMMENT ON COLUMN dev_strategy_version.published_by IS '策略版本发布人标识';
COMMENT ON COLUMN dev_strategy_version.created_at IS '创建时间';
COMMENT ON COLUMN dev_strategy_version.created_by IS '创建人标识';
COMMENT ON COLUMN dev_strategy_version.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_strategy_version.updated_by IS '最后修改人标识';
COMMENT ON COLUMN dev_strategy_version.version IS '乐观锁版本号';

COMMENT ON TABLE dev_strategy_release IS '策略版本应用到产品、设备组或单设备的不可变发布事实';
COMMENT ON COLUMN dev_strategy_release.id IS '策略发布 UUIDv7 标识';
COMMENT ON COLUMN dev_strategy_release.product_id IS '策略发布所属产品标识';
COMMENT ON COLUMN dev_strategy_release.strategy_version_id IS '本次发布使用的已发布策略版本标识';
COMMENT ON COLUMN dev_strategy_release.strategy_type IS '本次发布的稳定策略类型';
COMMENT ON COLUMN dev_strategy_release.release_sequence IS '产品内单调递增策略发布序号';
COMMENT ON COLUMN dev_strategy_release.scope_type IS '产品、设备组或单设备作用范围';
COMMENT ON COLUMN dev_strategy_release.scope_id IS '对应作用范围的业务标识';
COMMENT ON COLUMN dev_strategy_release.group_priority IS '设备组发布时的优先级审计快照';
COMMENT ON COLUMN dev_strategy_release.action IS '普通发布或引用历史版本的回滚发布';
COMMENT ON COLUMN dev_strategy_release.rollback_of_release_id IS '回滚操作对应的历史策略发布标识';
COMMENT ON COLUMN dev_strategy_release.reason IS '发布或回滚业务原因';
COMMENT ON COLUMN dev_strategy_release.idempotency_key IS '客户端提供的策略发布幂等键';
COMMENT ON COLUMN dev_strategy_release.created_at IS '发布时间';
COMMENT ON COLUMN dev_strategy_release.created_by IS '发布管理员标识';

COMMENT ON FUNCTION dev_validate_device_group_membership() IS '拒绝跨产品或无效设备组成员关系';
COMMENT ON FUNCTION dev_validate_strategy_release() IS '校验策略版本与多态作用范围的一致性';
COMMENT ON FUNCTION dev_reject_published_strategy_version_change() IS '阻止已发布策略版本被修改或删除';

-- 暂挂在物模型与协议页面下，策略工作台交付后再发布独立可见菜单。
INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000249', '019b0000-0000-7000-8000-000000000240',
     '查看设备组', 'BUTTON', 'device:group:view', 80, FALSE, TRUE),
    ('019b0000-0000-7000-8000-00000000024a', '019b0000-0000-7000-8000-000000000240',
     '维护设备组', 'BUTTON', 'device:group:edit', 90, FALSE, TRUE),
    ('019b0000-0000-7000-8000-00000000024b', '019b0000-0000-7000-8000-000000000240',
     '查看有效策略', 'BUTTON', 'device:strategy:view', 100, FALSE, TRUE),
    ('019b0000-0000-7000-8000-00000000024c', '019b0000-0000-7000-8000-000000000240',
     '编辑策略草稿', 'BUTTON', 'device:strategy:edit', 110, FALSE, TRUE),
    ('019b0000-0000-7000-8000-00000000024d', '019b0000-0000-7000-8000-000000000240',
     '发布设备策略', 'BUTTON', 'device:strategy:publish', 120, FALSE, TRUE);
