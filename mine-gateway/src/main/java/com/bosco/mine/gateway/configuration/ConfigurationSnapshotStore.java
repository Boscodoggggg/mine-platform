package com.bosco.mine.gateway.configuration;

/**
 * 配置快照在运行节点本地的持久化边界。
 */
@FunctionalInterface
public interface ConfigurationSnapshotStore {

    /**
     * 持久化一份已经完成校验并激活的配置快照。
     *
     * @param snapshot 已激活配置快照
     */
    void persist(ConfigurationSnapshot snapshot);
}
