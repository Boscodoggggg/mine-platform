package com.bosco.mine.server.modules.device.dto;

import java.util.UUID;

/**
 * 设备凭据签发响应；其中明文密钥只在签发或轮换成功时返回一次。
 *
 * @param id        凭据标识
 * @param deviceId  设备标识
 * @param username  MQTT 用户名
 * @param clientId  MQTT 客户端标识
 * @param secret    只返回一次的明文密钥
 * @param topicRoot 主题根路径
 * @param upTopic   设备上行主题前缀
 * @param downTopic 设备下行主题前缀
 * @param version   乐观锁版本
 */
public record DeviceCredentialResponse(
        UUID id,
        UUID deviceId,
        String username,
        String clientId,
        String secret,
        String topicRoot,
        String upTopic,
        String downTopic,
        long version
) {
}
