package com.bosco.mine.server.modules.notification.dto;

import jakarta.validation.constraints.Min;

/**
 * 通知升级策略派生草稿或归档动作请求。
 *
 * @param version 期望策略聚合版本号
 */
public record NotificationPolicyActionRequest(@Min(0) long version) {
}
