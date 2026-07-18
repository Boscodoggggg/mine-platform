-- 为坐标转换草稿增加独立于业务版本号的乐观锁和更新审计字段。
ALTER TABLE mine_coordinate_transform_version
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_by UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_mine_coordinate_transform_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_mine_coordinate_transform_audit
        CHECK (updated_at >= created_at AND version >= 0);

COMMENT ON COLUMN mine_coordinate_transform_version.updated_at IS '转换版本最近更新时间';
COMMENT ON COLUMN mine_coordinate_transform_version.updated_by IS '转换版本最近更新用户标识';
COMMENT ON COLUMN mine_coordinate_transform_version.version IS '转换草稿乐观锁版本号';
