-- 在不修改已发布 V29 的前提下扩展设备组间接激活凭证。
ALTER TABLE dev_retention_preview_evidence
    ADD COLUMN activation_type VARCHAR(32),
    ADD COLUMN change_type VARCHAR(32),
    ADD COLUMN group_version BIGINT;

UPDATE dev_retention_preview_evidence
SET activation_type = 'STRATEGY_RELEASE';

ALTER TABLE dev_retention_preview_evidence
    ALTER COLUMN activation_type SET NOT NULL,
    DROP CONSTRAINT ck_dev_retention_preview_activation,
    ADD CONSTRAINT ck_dev_retention_preview_activation_type
        CHECK (activation_type IN ('STRATEGY_RELEASE', 'GROUP_CHANGE')),
    ADD CONSTRAINT ck_dev_retention_preview_activation CHECK (
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
    );

DROP INDEX idx_dev_retention_preview_expiry;
CREATE INDEX idx_dev_retention_preview_expiry
    ON dev_retention_preview_evidence (expires_at, id)
    WHERE activated_at IS NULL;

COMMENT ON COLUMN dev_retention_preview_evidence.activation_type IS '直接策略发布或设备组间接激活类型';
COMMENT ON COLUMN dev_retention_preview_evidence.change_type IS '设备组成员、优先级或归档变化类型';
COMMENT ON COLUMN dev_retention_preview_evidence.group_version IS '设备组拟变更预览绑定的乐观锁版本';
COMMENT ON COLUMN dev_retention_preview_evidence.group_priority IS '直接发布的设备组优先级或拟变更后的目标优先级';
COMMENT ON COLUMN dev_retention_preview_evidence.candidate_hash IS '候选策略内容或设备组变化身份的规范化哈希';
COMMENT ON COLUMN dev_retention_preview_evidence.activated_at IS '凭证完成一次性发布或设备组变化激活的时间';
