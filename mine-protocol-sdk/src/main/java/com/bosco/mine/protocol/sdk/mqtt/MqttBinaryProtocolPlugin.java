package com.bosco.mine.protocol.sdk.mqtt;

/**
 * 第三方 MQTT 二进制 Payload 插件的稳定入口。
 *
 * <p>实现类必须提供公开无参构造器并通过 {@link java.util.ServiceLoader} 注册。插件只解析 Broker
 * 已经完成消息边界切分的完整 Payload，不得覆盖平台提供的可信产品和设备身份。</p>
 */
public interface MqttBinaryProtocolPlugin {

    /** 当前平台支持的插件 API 主版本。 */
    int API_MAJOR_VERSION = 1;

    /** @return 全局唯一、可用于产品配置的协议键 */
    String protocolKey();

    /** @return 实际写入统一消息和审计日志的协议版本 */
    String protocolVersion();

    /** @return 插件编译时依赖的 API 主版本 */
    default int apiMajorVersion() {
        return API_MAJOR_VERSION;
    }

    /**
     * 解码一条完整 MQTT 二进制 Payload。
     *
     * @param context 平台确认的可信设备上下文
     * @param payload 完整 Payload 的独立字节副本
     * @return 标准遥测中间结果
     * @throws MqttBinaryProtocolException Payload 不符合协议时抛出受控异常
     */
    MqttBinaryDecodedMessage decode(MqttBinaryDecodeContext context, byte[] payload);
}
