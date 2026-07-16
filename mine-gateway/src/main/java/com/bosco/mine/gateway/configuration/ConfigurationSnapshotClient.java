package com.bosco.mine.gateway.configuration;

import java.util.UUID;

/**
 * 从管理服务安全拉取指定不可变配置快照的边界。
 */
@FunctionalInterface
public interface ConfigurationSnapshotClient {

    /**
     * 拉取指定配置发布快照。
     *
     * @param releaseId 配置发布标识
     * @return 完整配置快照
     * @throws ConfigurationFetchException 网络或服务端暂时不可用
     */
    ConfigurationSnapshot fetch(UUID releaseId);

    /**
     * 回报当前节点已成功加载指定配置发布。
     *
     * <p>默认实现用于不需要状态闭环的本地替代实现，生产 HTTP 客户端会覆盖该方法。</p>
     *
     * @param releaseId 配置发布标识
     * @param actualHash 节点实际加载的内容哈希
     */
    default void reportLoaded(UUID releaseId, String actualHash) {
        // 可选能力由具体配置客户端实现。
    }
}
