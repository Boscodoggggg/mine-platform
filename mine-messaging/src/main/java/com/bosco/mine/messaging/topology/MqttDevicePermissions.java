package com.bosco.mine.messaging.topology;

import java.util.regex.Pattern;

/**
 * 承载 RabbitMQ MQTT 插件为单个设备生成的最小资源和主题权限正则。
 * 设备标识禁止点号、斜杠和 MQTT 通配符，避免 MQTT 主题转换为 AMQP 路由键时越权。
 */
public final class MqttDevicePermissions {

    private static final Pattern SAFE_DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_PRODUCT_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");

    private final String configurePattern;
    private final String writePattern;
    private final String readPattern;
    private final String topicWritePattern;
    private final String topicReadPattern;

    private MqttDevicePermissions(
            String configurePattern,
            String writePattern,
            String readPattern,
            String topicWritePattern,
            String topicReadPattern
    ) {
        this.configurePattern = configurePattern;
        this.writePattern = writePattern;
        this.readPattern = readPattern;
        this.topicWritePattern = topicWritePattern;
        this.topicReadPattern = topicReadPattern;
    }

    /**
     * 为一个安全设备标识生成 RabbitMQ 权限正则。
     *
     * @param deviceId 设备标识，同时作为 MQTT clientId
     * @return 设备最小权限
     */
    public static MqttDevicePermissions forDevice(String deviceId) {
        return forDevicePattern("[^.]+", deviceId);
    }

    /**
     * 为指定产品下的安全设备标识生成精确 RabbitMQ 权限正则。
     *
     * @param productKey 产品编码
     * @param deviceId   设备标识，同时作为 MQTT clientId
     * @return 锁定产品和设备两级身份的最小权限
     */
    public static MqttDevicePermissions forDevice(String productKey, String deviceId) {
        if (productKey == null || !SAFE_PRODUCT_KEY.matcher(productKey).matches()) {
            throw new IllegalArgumentException("productKey 必须符合小写字母、数字和连字符编码规则");
        }
        return forDevicePattern(productKey, deviceId);
    }

    private static MqttDevicePermissions forDevicePattern(String productPattern, String deviceId) {
        if (deviceId == null || !SAFE_DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("deviceId 只能包含字母、数字、下划线和连字符，长度为 1 到 64");
        }
        String queuePattern = "^mqtt-subscription-" + deviceId + "qos[01]$";
        String resourcePattern = "^(amq\\.topic|mqtt-subscription-" + deviceId + "qos[01])$";
        return new MqttDevicePermissions(
                queuePattern,
                resourcePattern,
                resourcePattern,
                // 上行动作采用白名单，防止设备自行扩展未受治理的主题。
                "^mine\\.v1\\." + productPattern + "\\." + deviceId
                        + "\\.up\\.(telemetry|location|command-ack)$",
                "^mine\\.v1\\." + productPattern + "\\." + deviceId + "\\.down\\.[^.]+$"
        );
    }

    /**
     * 获取仅允许声明本 clientId 会话队列的资源正则。
     *
     * @return configure 权限正则
     */
    public String configurePattern() {
        return configurePattern;
    }

    /**
     * 获取允许写入 MQTT 交换机和绑定本会话队列的资源正则。
     *
     * @return write 权限正则
     */
    public String writePattern() {
        return writePattern;
    }

    /**
     * 获取允许读取 MQTT 交换机和本会话队列的资源正则。
     *
     * @return read 权限正则
     */
    public String readPattern() {
        return readPattern;
    }

    /**
     * 获取只允许发布本设备上行主题的 topic write 正则。
     *
     * @return topic write 权限正则
     */
    public String topicWritePattern() {
        return topicWritePattern;
    }

    /**
     * 获取只允许订阅本设备下行主题的 topic read 正则。
     *
     * @return topic read 权限正则
     */
    public String topicReadPattern() {
        return topicReadPattern;
    }
}
