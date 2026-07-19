package com.bosco.mine.protocol.sdk.mqtt;

import java.util.regex.Pattern;

/**
 * 表示平台在调用 MQTT 二进制插件前已经确认的可信设备上下文。
 *
 * @param productKey 产品编码
 * @param clientId MQTT 客户端标识
 * @param deviceCode 设备自然编码
 */
public record MqttBinaryDecodeContext(String productKey, String clientId, String deviceCode) {

    private static final Pattern PRODUCT_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DEVICE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /** 校验可信上下文使用的受控标识。 */
    public MqttBinaryDecodeContext {
        if (productKey == null || !PRODUCT_KEY.matcher(productKey).matches()) {
            throw new IllegalArgumentException("productKey 格式不合法");
        }
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalArgumentException("clientId 格式不合法");
        }
        if (deviceCode == null || !DEVICE_CODE.matcher(deviceCode).matches()) {
            throw new IllegalArgumentException("deviceCode 格式不合法");
        }
    }
}
