-- 协议黄金样本、配置包和发布事实共同构成阶段二配置控制面。
CREATE TABLE dev_protocol_sample (
    id                    UUID         NOT NULL,
    mapping_version_id    UUID         NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    raw_payload           JSONB        NOT NULL,
    expected_success      BOOLEAN      NOT NULL,
    expected_properties   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    expected_error_code   VARCHAR(128),
    sort_order            INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            UUID,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            UUID,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dev_protocol_sample PRIMARY KEY (id),
    CONSTRAINT uk_dev_protocol_sample_version_name UNIQUE (mapping_version_id, name),
    CONSTRAINT fk_dev_protocol_sample_mapping FOREIGN KEY (mapping_version_id)
        REFERENCES dev_protocol_mapping_version (id),
    CONSTRAINT ck_dev_protocol_sample_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dev_protocol_sample_payload CHECK (jsonb_typeof(raw_payload) = 'object'),
    CONSTRAINT ck_dev_protocol_sample_properties CHECK (jsonb_typeof(expected_properties) = 'object'),
    CONSTRAINT ck_dev_protocol_sample_expectation CHECK (
        (expected_success = TRUE AND expected_error_code IS NULL)
        OR (expected_success = FALSE AND btrim(expected_error_code) <> '')
    ),
    CONSTRAINT ck_dev_protocol_sample_sort CHECK (sort_order >= 0),
    CONSTRAINT ck_dev_protocol_sample_version CHECK (version >= 0)
);

CREATE INDEX idx_dev_protocol_sample_mapping_sort
    ON dev_protocol_sample (mapping_version_id, sort_order, id);

CREATE TABLE dev_protocol_test_run (
    id                    UUID         NOT NULL,
    mapping_version_id    UUID         NOT NULL,
    definition_hash       VARCHAR(71)  NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    total_samples         INTEGER      NOT NULL,
    passed_samples        INTEGER      NOT NULL,
    failed_samples        INTEGER      NOT NULL,
    result_json           JSONB        NOT NULL,
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMPTZ  NOT NULL,
    triggered_by          UUID,
    CONSTRAINT pk_dev_protocol_test_run PRIMARY KEY (id),
    CONSTRAINT fk_dev_protocol_test_run_mapping FOREIGN KEY (mapping_version_id)
        REFERENCES dev_protocol_mapping_version (id),
    CONSTRAINT ck_dev_protocol_test_run_hash CHECK (definition_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_dev_protocol_test_run_status CHECK (status IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_dev_protocol_test_run_counts CHECK (
        total_samples BETWEEN 1 AND 200
        AND passed_samples >= 0
        AND failed_samples >= 0
        AND passed_samples + failed_samples = total_samples
    ),
    CONSTRAINT ck_dev_protocol_test_run_result CHECK (jsonb_typeof(result_json) = 'object'),
    CONSTRAINT ck_dev_protocol_test_run_time CHECK (completed_at >= started_at)
);

CREATE INDEX idx_dev_protocol_test_run_mapping_time
    ON dev_protocol_test_run (mapping_version_id, completed_at DESC, id);

CREATE TABLE dev_config_package (
    id                            UUID         NOT NULL,
    product_id                    UUID         NOT NULL,
    package_sequence              BIGINT       NOT NULL,
    model_version_id              UUID         NOT NULL,
    protocol_mapping_version_id   UUID         NOT NULL,
    content_hash                  VARCHAR(71)  NOT NULL,
    snapshot                      JSONB        NOT NULL,
    change_reason                 VARCHAR(500) NOT NULL,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                    UUID,
    CONSTRAINT pk_dev_config_package PRIMARY KEY (id),
    CONSTRAINT uk_dev_config_package_product_sequence UNIQUE (product_id, package_sequence),
    CONSTRAINT fk_dev_config_package_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT fk_dev_config_package_model FOREIGN KEY (model_version_id) REFERENCES dev_model_version (id),
    CONSTRAINT fk_dev_config_package_mapping FOREIGN KEY (protocol_mapping_version_id)
        REFERENCES dev_protocol_mapping_version (id),
    CONSTRAINT ck_dev_config_package_sequence CHECK (package_sequence > 0),
    CONSTRAINT ck_dev_config_package_hash CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_dev_config_package_snapshot CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT ck_dev_config_package_reason CHECK (btrim(change_reason) <> '')
);

CREATE UNIQUE INDEX uk_dev_config_package_product_hash
    ON dev_config_package (product_id, content_hash);

CREATE TABLE dev_config_release (
    id                       UUID         NOT NULL,
    product_id               UUID         NOT NULL,
    package_id               UUID         NOT NULL,
    release_sequence         BIGINT       NOT NULL,
    action                   VARCHAR(32)  NOT NULL,
    rollback_of_release_id   UUID,
    reason                   VARCHAR(500) NOT NULL,
    idempotency_key          VARCHAR(128) NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               UUID,
    CONSTRAINT pk_dev_config_release PRIMARY KEY (id),
    CONSTRAINT uk_dev_config_release_product_sequence UNIQUE (product_id, release_sequence),
    CONSTRAINT fk_dev_config_release_product FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT fk_dev_config_release_package FOREIGN KEY (package_id) REFERENCES dev_config_package (id),
    CONSTRAINT fk_dev_config_release_rollback FOREIGN KEY (rollback_of_release_id)
        REFERENCES dev_config_release (id),
    CONSTRAINT ck_dev_config_release_sequence CHECK (release_sequence > 0),
    CONSTRAINT ck_dev_config_release_action CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_dev_config_release_rollback CHECK (
        (action = 'PUBLISH' AND rollback_of_release_id IS NULL)
        OR (action = 'ROLLBACK' AND rollback_of_release_id IS NOT NULL)
    ),
    CONSTRAINT ck_dev_config_release_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_dev_config_release_idempotency CHECK (btrim(idempotency_key) <> '')
);

CREATE UNIQUE INDEX uk_dev_config_release_creator_idempotency
    ON dev_config_release (created_by, idempotency_key)
    WHERE created_by IS NOT NULL;

CREATE INDEX idx_dev_config_release_product_time
    ON dev_config_release (product_id, release_sequence DESC);

CREATE TABLE dev_config_release_target (
    id            UUID        NOT NULL,
    release_id    UUID        NOT NULL,
    target_type   VARCHAR(32) NOT NULL,
    target_id     UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dev_config_release_target PRIMARY KEY (id),
    CONSTRAINT uk_dev_config_release_target UNIQUE (release_id, target_type, target_id),
    CONSTRAINT fk_dev_config_release_target_release FOREIGN KEY (release_id)
        REFERENCES dev_config_release (id),
    CONSTRAINT ck_dev_config_release_target_type CHECK (target_type IN ('PRODUCT', 'GROUP', 'DEVICE'))
);

CREATE INDEX idx_dev_config_release_target_lookup
    ON dev_config_release_target (target_type, target_id, release_id);

CREATE TABLE dev_config_node_status (
    release_id      UUID         NOT NULL,
    node_id         VARCHAR(128) NOT NULL,
    node_type       VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    actual_hash     VARCHAR(71),
    message         VARCHAR(500),
    loaded_at       TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dev_config_node_status PRIMARY KEY (release_id, node_id),
    CONSTRAINT fk_dev_config_node_status_release FOREIGN KEY (release_id)
        REFERENCES dev_config_release (id),
    CONSTRAINT ck_dev_config_node_status_node CHECK (btrim(node_id) <> ''),
    CONSTRAINT ck_dev_config_node_status_type CHECK (node_type IN ('GATEWAY', 'TELEMETRY', 'SERVER')),
    CONSTRAINT ck_dev_config_node_status_status CHECK (status IN ('PENDING', 'LOADED', 'FAILED')),
    CONSTRAINT ck_dev_config_node_status_hash CHECK (
        actual_hash IS NULL OR actual_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_dev_config_node_status_loaded CHECK (
        (status = 'LOADED' AND actual_hash IS NOT NULL AND loaded_at IS NOT NULL)
        OR status <> 'LOADED'
    )
);

CREATE INDEX idx_dev_config_node_status_state
    ON dev_config_node_status (status, updated_at DESC);

-- 配置包和发布事实只允许新增，回滚也必须创建更高序号的新发布。
CREATE FUNCTION dev_reject_immutable_config_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '已发布配置事实不可修改或删除' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_dev_config_package_immutable
BEFORE UPDATE OR DELETE ON dev_config_package
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

CREATE TRIGGER trg_dev_config_release_immutable
BEFORE UPDATE OR DELETE ON dev_config_release
FOR EACH ROW EXECUTE FUNCTION dev_reject_immutable_config_change();

COMMENT ON TABLE dev_protocol_sample IS '协议版本发布门禁使用的黄金样本';
COMMENT ON COLUMN dev_protocol_sample.id IS '协议样本 UUIDv7 标识';
COMMENT ON COLUMN dev_protocol_sample.mapping_version_id IS '所属协议映射版本标识';
COMMENT ON COLUMN dev_protocol_sample.name IS '产品协议版本内唯一的样本名称';
COMMENT ON COLUMN dev_protocol_sample.raw_payload IS '脱敏后的原始 MQTT JSON 样本';
COMMENT ON COLUMN dev_protocol_sample.expected_success IS '样本是否期望解析成功';
COMMENT ON COLUMN dev_protocol_sample.expected_properties IS '成功时期望生成的统一物模型属性';
COMMENT ON COLUMN dev_protocol_sample.expected_error_code IS '失败时期望得到的稳定错误码';
COMMENT ON COLUMN dev_protocol_sample.sort_order IS '工作台展示和回放顺序';
COMMENT ON COLUMN dev_protocol_sample.created_at IS '创建时间';
COMMENT ON COLUMN dev_protocol_sample.created_by IS '创建人标识';
COMMENT ON COLUMN dev_protocol_sample.updated_at IS '最后更新时间';
COMMENT ON COLUMN dev_protocol_sample.updated_by IS '最后修改人标识';
COMMENT ON COLUMN dev_protocol_sample.version IS '乐观锁版本号';

COMMENT ON TABLE dev_protocol_test_run IS '协议黄金样本的一次不可变回放结果';
COMMENT ON COLUMN dev_protocol_test_run.id IS '测试运行 UUIDv7 标识';
COMMENT ON COLUMN dev_protocol_test_run.mapping_version_id IS '被测试的协议映射版本标识';
COMMENT ON COLUMN dev_protocol_test_run.definition_hash IS '测试时协议定义的规范化 SHA-256';
COMMENT ON COLUMN dev_protocol_test_run.status IS '全部通过或存在失败的测试状态';
COMMENT ON COLUMN dev_protocol_test_run.total_samples IS '本次回放样本总数';
COMMENT ON COLUMN dev_protocol_test_run.passed_samples IS '本次通过样本数';
COMMENT ON COLUMN dev_protocol_test_run.failed_samples IS '本次失败样本数';
COMMENT ON COLUMN dev_protocol_test_run.result_json IS '逐样本结构化回放结果';
COMMENT ON COLUMN dev_protocol_test_run.started_at IS '测试开始时间';
COMMENT ON COLUMN dev_protocol_test_run.completed_at IS '测试完成时间';
COMMENT ON COLUMN dev_protocol_test_run.triggered_by IS '触发测试的管理员标识';

COMMENT ON TABLE dev_config_package IS '物模型与协议映射组成的不可变运行配置包';
COMMENT ON COLUMN dev_config_package.id IS '配置包 UUIDv7 标识';
COMMENT ON COLUMN dev_config_package.product_id IS '配置包所属设备产品标识';
COMMENT ON COLUMN dev_config_package.package_sequence IS '产品内单调递增配置包序号';
COMMENT ON COLUMN dev_config_package.model_version_id IS '配置包引用的已发布物模型版本';
COMMENT ON COLUMN dev_config_package.protocol_mapping_version_id IS '配置包引用的已发布协议映射版本';
COMMENT ON COLUMN dev_config_package.content_hash IS '规范化完整快照的 SHA-256';
COMMENT ON COLUMN dev_config_package.snapshot IS '运行节点可直接编译的完整非敏感快照';
COMMENT ON COLUMN dev_config_package.change_reason IS '创建配置包的业务原因';
COMMENT ON COLUMN dev_config_package.created_at IS '创建时间';
COMMENT ON COLUMN dev_config_package.created_by IS '创建人标识';

COMMENT ON TABLE dev_config_release IS '配置包应用到产品范围的不可变发布事实';
COMMENT ON COLUMN dev_config_release.id IS '配置发布 UUIDv7 标识';
COMMENT ON COLUMN dev_config_release.product_id IS '发布所属设备产品标识';
COMMENT ON COLUMN dev_config_release.package_id IS '本次发布使用的不可变配置包标识';
COMMENT ON COLUMN dev_config_release.release_sequence IS '产品内单调递增发布序号';
COMMENT ON COLUMN dev_config_release.action IS '普通发布或基于历史包的新回滚发布';
COMMENT ON COLUMN dev_config_release.rollback_of_release_id IS '回滚操作针对的历史发布标识';
COMMENT ON COLUMN dev_config_release.reason IS '发布或回滚业务原因';
COMMENT ON COLUMN dev_config_release.idempotency_key IS '客户端提供的发布幂等键';
COMMENT ON COLUMN dev_config_release.created_at IS '发布时间';
COMMENT ON COLUMN dev_config_release.created_by IS '发布管理员标识';

COMMENT ON TABLE dev_config_release_target IS '配置发布的产品、设备组或设备作用目标';
COMMENT ON COLUMN dev_config_release_target.id IS '发布目标 UUIDv7 标识';
COMMENT ON COLUMN dev_config_release_target.release_id IS '所属配置发布标识';
COMMENT ON COLUMN dev_config_release_target.target_type IS '产品、设备组或单设备目标类型';
COMMENT ON COLUMN dev_config_release_target.target_id IS '对应目标类型的业务标识';
COMMENT ON COLUMN dev_config_release_target.created_at IS '创建时间';

COMMENT ON TABLE dev_config_node_status IS '运行节点加载一次配置发布的状态';
COMMENT ON COLUMN dev_config_node_status.release_id IS '对应配置发布标识';
COMMENT ON COLUMN dev_config_node_status.node_id IS '部署环境内稳定节点标识';
COMMENT ON COLUMN dev_config_node_status.node_type IS 'Gateway、Telemetry 或 Server 节点类型';
COMMENT ON COLUMN dev_config_node_status.status IS '待加载、已加载或加载失败状态';
COMMENT ON COLUMN dev_config_node_status.actual_hash IS '节点实际加载快照的 SHA-256';
COMMENT ON COLUMN dev_config_node_status.message IS '脱敏后的加载结果说明';
COMMENT ON COLUMN dev_config_node_status.loaded_at IS '节点成功完成原子切换时间';
COMMENT ON COLUMN dev_config_node_status.updated_at IS '节点最后上报时间';

COMMENT ON FUNCTION dev_reject_immutable_config_change() IS '阻止配置包和发布事实被原地修改或删除';

-- 模型页面新增样本查看编辑、回放测试、配置发布和回滚五项独立权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000243', '019b0000-0000-7000-8000-000000000240',
     '查看协议样本', 'BUTTON', 'device:protocol:sample:view', 30, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000244', '019b0000-0000-7000-8000-000000000240',
     '编辑协议样本', 'BUTTON', 'device:protocol:sample:edit', 40, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000245', '019b0000-0000-7000-8000-000000000240',
     '回放协议样本', 'BUTTON', 'device:protocol:test', 50, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000246', '019b0000-0000-7000-8000-000000000240',
     '发布设备配置', 'BUTTON', 'device:configuration:publish', 60, FALSE, TRUE),
    ('019b0000-0000-7000-8000-000000000247', '019b0000-0000-7000-8000-000000000240',
     '回滚设备配置', 'BUTTON', 'device:configuration:rollback', 70, FALSE, TRUE);
