package com.bosco.mine.simulator.profile;

import java.util.Objects;

/**
 * 单台模拟设备的连接与上报配置。
 *
 * @param id 配置唯一标识
 * @param name 配置显示名称
 * @param transportMode 传输模式
 * @param host 目标地址或监听地址
 * @param port 目标端口或监听端口
 * @param tlsEnabled 是否启用 TLS
 * @param productKey 产品标识
 * @param deviceCode 设备编码
 * @param clientId MQTT 客户端标识
 * @param username MQTT 用户名
 * @param publishTopic MQTT 发布主题
 * @param payloadFormat 报文格式
 * @param payloadTemplate 报文模板
 * @param intervalMillis 周期发送间隔，单位毫秒
 * @param qos MQTT 服务质量等级
 */
public record SimulatorProfile(
        String id,
        String name,
        TransportMode transportMode,
        String host,
        int port,
        boolean tlsEnabled,
        String productKey,
        String deviceCode,
        String clientId,
        String username,
        String publishTopic,
        PayloadFormat payloadFormat,
        String payloadTemplate,
        long intervalMillis,
        int qos) {

    /**
     * 规范化并校验配置，避免无效配置进入运行时。
     */
    public SimulatorProfile {
        id = requireText(id, "配置标识");
        name = requireText(name, "配置名称");
        transportMode = Objects.requireNonNull(transportMode, "传输模式不能为空");
        host = requireText(host, "主机地址");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        }
        productKey = normalize(productKey);
        deviceCode = normalize(deviceCode);
        clientId = normalize(clientId);
        username = normalize(username);
        publishTopic = normalize(publishTopic);
        payloadFormat = Objects.requireNonNull(payloadFormat, "报文格式不能为空");
        payloadTemplate = requireText(payloadTemplate, "报文模板");
        if (intervalMillis < 100) {
            throw new IllegalArgumentException("发送间隔不能小于 100 毫秒");
        }
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("MQTT QoS 必须是 0、1 或 2");
        }
        if (transportMode == TransportMode.MQTT) {
            clientId = requireText(clientId, "MQTT 客户端标识");
            publishTopic = requireText(publishTopic, "MQTT 发布主题");
        }
    }

    private static String requireText(String value, String label) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
