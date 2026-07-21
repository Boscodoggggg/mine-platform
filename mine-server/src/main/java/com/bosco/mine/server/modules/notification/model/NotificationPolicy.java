package com.bosco.mine.server.modules.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知升级策略根配置快照。
 *
 * @param id 策略标识
 * @param policyCode 稳定策略编码
 * @param name 策略名称
 * @param description 策略说明
 * @param priority 匹配优先级
 * @param status 活跃或归档状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param archivedAt 归档时间
 * @param version 聚合乐观锁版本号
 */
public record NotificationPolicy(
        UUID id,
        String policyCode,
        String name,
        String description,
        int priority,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
}
