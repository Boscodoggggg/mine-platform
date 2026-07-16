package com.bosco.mine.protocol.engine.json;

import java.time.Instant;
import java.util.Map;

/**
 * 协议执行的结构化结果，失败时不会泄漏未捕获解析异常。
 *
 * @param success 是否解析成功
 * @param productKey 报文产品编码
 * @param deviceCode 报文设备编码
 * @param bootId 设备启动批次
 * @param sequence 设备消息序号
 * @param occurredAt 设备侧发生时间
 * @param replayed 是否为补传
 * @param properties 统一物模型属性
 * @param errorCode 失败错误码
 * @param errorMessage 脱敏错误说明
 */
public record ProtocolExecutionResult(
        boolean success,
        String productKey,
        String deviceCode,
        String bootId,
        Long sequence,
        Instant occurredAt,
        boolean replayed,
        Map<String, Object> properties,
        String errorCode,
        String errorMessage
) {

    /**
     * 防止调用方修改已经发布的解析结果。
     */
    public ProtocolExecutionResult {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * 创建成功结果。
     */
    public static ProtocolExecutionResult succeeded(
            String productKey,
            String deviceCode,
            String bootId,
            Long sequence,
            Instant occurredAt,
            boolean replayed,
            Map<String, Object> properties
    ) {
        return new ProtocolExecutionResult(
                true, productKey, deviceCode, bootId, sequence, occurredAt, replayed,
                properties, null, null);
    }

    /**
     * 创建失败结果，不保留部分解析属性。
     */
    public static ProtocolExecutionResult failed(String code, String message) {
        return new ProtocolExecutionResult(
                false, null, null, null, null, null, false, Map.of(), code, message);
    }
}
