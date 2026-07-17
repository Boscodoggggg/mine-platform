-- 数据保留预览凭证把精确策略版本、作用域和分层解析结果绑定到一次不可逆激活。
CREATE TABLE dev_retention_preview_evidence (
    id                      UUID        NOT NULL,
    operator_id             UUID,
    activation_type         VARCHAR(32) NOT NULL,
    change_type             VARCHAR(32),
    group_version           BIGINT,
    strategy_version_id     UUID        NOT NULL,
    product_id              UUID        NOT NULL,
    scope_type              VARCHAR(32) NOT NULL,
    scope_id                UUID        NOT NULL,
    group_priority          INTEGER,
    candidate_hash          VARCHAR(71) NOT NULL,
    resolution_hash         VARCHAR(71) NOT NULL,
    observed_at             TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    activated_release_id    UUID,
    activated_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dev_retention_preview_evidence PRIMARY KEY (id),
    CONSTRAINT fk_dev_retention_preview_version
        FOREIGN KEY (strategy_version_id) REFERENCES dev_strategy_version (id),
    CONSTRAINT fk_dev_retention_preview_product
        FOREIGN KEY (product_id) REFERENCES dev_product (id),
    CONSTRAINT fk_dev_retention_preview_release
        FOREIGN KEY (activated_release_id) REFERENCES dev_strategy_release (id),
    CONSTRAINT uk_dev_retention_preview_release UNIQUE (activated_release_id),
    CONSTRAINT ck_dev_retention_preview_scope
        CHECK (scope_type IN ('PRODUCT', 'GROUP', 'DEVICE')),
    CONSTRAINT ck_dev_retention_preview_activation_type
        CHECK (activation_type IN ('STRATEGY_RELEASE', 'GROUP_CHANGE')),
    CONSTRAINT ck_dev_retention_preview_priority CHECK (
        (scope_type = 'GROUP' AND group_priority BETWEEN 0 AND 1000)
        OR (scope_type <> 'GROUP' AND group_priority IS NULL)
    ),
    CONSTRAINT ck_dev_retention_preview_candidate_hash
        CHECK (candidate_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_dev_retention_preview_resolution_hash
        CHECK (resolution_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_dev_retention_preview_expiry CHECK (expires_at > observed_at),
    CONSTRAINT ck_dev_retention_preview_activation CHECK (
        (
            activation_type = 'STRATEGY_RELEASE'
            AND change_type IS NULL
            AND group_version IS NULL
            AND (
                (activated_release_id IS NULL AND activated_at IS NULL)
                OR (activated_release_id IS NOT NULL AND activated_at IS NOT NULL)
            )
        )
        OR (
            activation_type = 'GROUP_CHANGE'
            AND scope_type = 'GROUP'
            AND change_type IN ('MEMBERSHIP', 'PRIORITY', 'GROUP_ARCHIVE')
            AND group_version >= 0
            AND activated_release_id IS NULL
        )
    )
);

CREATE INDEX idx_dev_retention_preview_expiry
    ON dev_retention_preview_evidence (expires_at, id)
    WHERE activated_at IS NULL;

COMMENT ON TABLE dev_retention_preview_evidence IS '数据保留不可逆激活前的短期精确影响预览凭证';
COMMENT ON COLUMN dev_retention_preview_evidence.id IS '预览凭证 UUIDv7 标识';
COMMENT ON COLUMN dev_retention_preview_evidence.operator_id IS '发起预览的管理员标识，内部调用允许为空';
COMMENT ON COLUMN dev_retention_preview_evidence.activation_type IS '直接策略发布或设备组间接激活类型';
COMMENT ON COLUMN dev_retention_preview_evidence.change_type IS '设备组成员、优先级或归档变化类型';
COMMENT ON COLUMN dev_retention_preview_evidence.group_version IS '设备组拟变更预览绑定的乐观锁版本';
COMMENT ON COLUMN dev_retention_preview_evidence.strategy_version_id IS '预览绑定的已保存策略版本标识';
COMMENT ON COLUMN dev_retention_preview_evidence.product_id IS '预览目标所属产品标识';
COMMENT ON COLUMN dev_retention_preview_evidence.scope_type IS '产品、设备组或单设备作用范围';
COMMENT ON COLUMN dev_retention_preview_evidence.scope_id IS '预览目标作用范围标识';
COMMENT ON COLUMN dev_retention_preview_evidence.group_priority IS '直接发布的设备组优先级或拟变更后的目标优先级';
COMMENT ON COLUMN dev_retention_preview_evidence.candidate_hash IS '候选策略内容或设备组变化身份的规范化哈希';
COMMENT ON COLUMN dev_retention_preview_evidence.resolution_hash IS '目标成员及逐设备最终保留策略解析哈希';
COMMENT ON COLUMN dev_retention_preview_evidence.observed_at IS '影响统计或安全停删预览生成时点';
COMMENT ON COLUMN dev_retention_preview_evidence.expires_at IS '凭证最晚允许激活时间';
COMMENT ON COLUMN dev_retention_preview_evidence.activated_release_id IS '凭证实际绑定的策略发布标识';
COMMENT ON COLUMN dev_retention_preview_evidence.activated_at IS '凭证完成一次性发布或设备组变化激活的时间';
COMMENT ON COLUMN dev_retention_preview_evidence.created_at IS '凭证持久化时间';
