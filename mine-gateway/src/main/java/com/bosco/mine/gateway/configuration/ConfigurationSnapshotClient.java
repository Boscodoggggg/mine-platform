package com.bosco.mine.gateway.configuration;

import java.util.UUID;

/**
 * 从管理服务安全拉取指定不可变配置快照的边界。
 */
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
     * <p>所有实现都必须完成状态回报；回报失败应作为瞬时错误抛出，调用方不得提前确认配置事件。</p>
     *
     * @param releaseId 配置发布标识
     * @param actualHash 节点实际加载的内容哈希
     */
    void reportLoaded(UUID releaseId, String actualHash);

    /**
     * 回报当前节点无法加载一个不可重试的配置发布。
     *
     * <p>错误摘要必须在调用前完成脱敏和长度限制；状态回报失败应作为瞬时错误重试，不能提前丢弃配置事件。</p>
     *
     * @param releaseId 配置发布标识
     * @param safeSummary 已脱敏且不超过服务端上限的错误摘要
     */
    void reportFailed(UUID releaseId, String safeSummary);
}
