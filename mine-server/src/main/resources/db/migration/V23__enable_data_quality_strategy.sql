-- 数据质量策略已经进入运行消费实施阶段，数据库白名单同步开放该稳定类型。
ALTER TABLE dev_strategy_version
    DROP CONSTRAINT ck_dev_strategy_version_type,
    ADD CONSTRAINT ck_dev_strategy_version_type
        CHECK (strategy_type IN ('ONLINE_STATUS', 'DATA_QUALITY'));

ALTER TABLE dev_strategy_release
    DROP CONSTRAINT ck_dev_strategy_release_type,
    ADD CONSTRAINT ck_dev_strategy_release_type
        CHECK (strategy_type IN ('ONLINE_STATUS', 'DATA_QUALITY'));
