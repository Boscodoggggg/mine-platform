package com.bosco.mine.protocol.engine.sample;

import java.util.Map;

/**
 * 发布门禁使用的一条不可变协议黄金样本。
 *
 * @param name 样本名称
 * @param payload 原始 UTF-8 JSON 字节
 * @param expectedSuccess 是否期望成功
 * @param expectedProperties 成功时期望的统一属性
 * @param expectedErrorCode 失败时期望的错误码
 */
public record ProtocolSample(
        String name,
        byte[] payload,
        boolean expectedSuccess,
        Map<String, Object> expectedProperties,
        String expectedErrorCode
) {

    /** 防御性复制可变字节数组和属性映射。 */
    public ProtocolSample {
        payload = payload == null ? new byte[0] : payload.clone();
        expectedProperties = expectedProperties == null ? Map.of() : Map.copyOf(expectedProperties);
    }

    /** 返回样本字节副本。 */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
