package com.bosco.mine.server.modules.device.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备 MQTT 凭据安全快照，不包含任何可用明文密钥。
 *
 * @param id         凭据标识
 * @param deviceId   设备标识
 * @param username   RabbitMQ 用户名和 MQTT 客户端标识
 * @param secretHash 密钥不可逆散列
 * @param secretHint 密钥末尾提示
 * @param topicRoot  设备主题根路径
 * @param status     凭据状态
 * @param updatedAt  最后更新时间
 * @param version    乐观锁版本
 */
public record DeviceCredential(
        UUID id,
        UUID deviceId,
        String username,
        String secretHash,
        String secretHint,
        String topicRoot,
        String status,
        Instant updatedAt,
        long version
) {
}
