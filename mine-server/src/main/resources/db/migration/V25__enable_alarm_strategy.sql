-- 告警策略复用三级控制面和运行快照，数据库白名单同步开放稳定类型。
ALTER TABLE dev_strategy_version
    DROP CONSTRAINT ck_dev_strategy_version_type,
    ADD CONSTRAINT ck_dev_strategy_version_type
        CHECK (strategy_type IN ('ONLINE_STATUS', 'DATA_QUALITY', 'ALARM'));

ALTER TABLE dev_strategy_release
    DROP CONSTRAINT ck_dev_strategy_release_type,
    ADD CONSTRAINT ck_dev_strategy_release_type
        CHECK (strategy_type IN ('ONLINE_STATUS', 'DATA_QUALITY', 'ALARM'));

ALTER TABLE dev_strategy_runtime_snapshot
    DROP CONSTRAINT ck_dev_strategy_runtime_snapshot_type,
    ADD CONSTRAINT ck_dev_strategy_runtime_snapshot_type
        CHECK (strategy_type IN ('DATA_QUALITY', 'ALARM'));
