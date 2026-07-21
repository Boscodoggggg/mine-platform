-- 为三维资源归档增加并发保护，避免重复或过期管理命令覆盖状态。
ALTER TABLE mine_scene_asset
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_mine_scene_asset_version CHECK (version >= 0);

COMMENT ON COLUMN mine_scene_asset.version IS '资源生命周期乐观锁版本';
