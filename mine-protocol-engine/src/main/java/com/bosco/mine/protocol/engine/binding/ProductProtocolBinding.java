package com.bosco.mine.protocol.engine.binding;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 描述一个产品发布到运行节点的协议路由和解析器绑定。
 *
 * @param transport 设备传输协议
 * @param payloadFormat 应用 Payload 格式
 * @param routingMode 建立解析器选择所使用的路由模式
 * @param parserType 解析器实现类型
 * @param pluginKey Java 插件键，配置化解析时为空
 * @param pluginVersion Java 插件版本，配置化解析时为空
 */
public record ProductProtocolBinding(
        Transport transport,
        PayloadFormat payloadFormat,
        RoutingMode routingMode,
        ParserType parserType,
        String pluginKey,
        String pluginVersion
) {

    private static final Pattern PLUGIN_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern PLUGIN_VERSION = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    /** 校验平台当前能够确定路由并安全执行的协议组合。 */
    public ProductProtocolBinding {
        Objects.requireNonNull(transport, "transport 不能为空");
        Objects.requireNonNull(payloadFormat, "payloadFormat 不能为空");
        Objects.requireNonNull(routingMode, "routingMode 不能为空");
        Objects.requireNonNull(parserType, "parserType 不能为空");

        boolean validCombination = transport == Transport.MQTT
                && payloadFormat == PayloadFormat.JSON
                && parserType == ParserType.CONFIGURATION
                && routingMode == RoutingMode.TRUSTED_IDENTITY
                || transport == Transport.MQTT
                && payloadFormat == PayloadFormat.BINARY
                && parserType == ParserType.JAVA_PLUGIN
                && routingMode == RoutingMode.TRUSTED_IDENTITY
                || transport == Transport.TCP
                && payloadFormat == PayloadFormat.BINARY
                && parserType == ParserType.JAVA_PLUGIN;
        if (!validCombination) {
            throw new IllegalArgumentException("产品协议绑定组合不受支持");
        }

        if (parserType == ParserType.CONFIGURATION) {
            if (pluginKey != null || pluginVersion != null) {
                throw new IllegalArgumentException("配置化解析不能声明 Java 插件");
            }
        } else {
            if (pluginKey == null || !PLUGIN_KEY.matcher(pluginKey).matches()) {
                throw new IllegalArgumentException("pluginKey 格式不合法");
            }
            if (pluginVersion == null || !PLUGIN_VERSION.matcher(pluginVersion).matches()) {
                throw new IllegalArgumentException("pluginVersion 格式不合法");
            }
        }
    }

    /** @return 是否使用配置化执行计划 */
    public boolean usesConfiguration() {
        return parserType == ParserType.CONFIGURATION;
    }

    /** @return 是否使用离线审核 Java 插件 */
    public boolean usesJavaPlugin() {
        return parserType == ParserType.JAVA_PLUGIN;
    }

    /** 设备传输协议。 */
    public enum Transport {
        /** MQTT Broker 接入。 */
        MQTT,
        /** 原始 TCP 长连接接入。 */
        TCP
    }

    /** 应用 Payload 格式。 */
    public enum PayloadFormat {
        /** UTF-8 JSON Payload。 */
        JSON,
        /** 厂商二进制 Payload。 */
        BINARY
    }

    /** 解析器选择路由模式。 */
    public enum RoutingMode {
        /** 在可信身份建立后按产品选择解析器。 */
        TRUSTED_IDENTITY,
        /** 兼容监听器在连接建立前显式绑定解析器。 */
        LEGACY_LISTENER
    }

    /** 解析器实现类型。 */
    public enum ParserType {
        /** 前台发布并编译为安全执行计划。 */
        CONFIGURATION,
        /** 随不可变镜像安装的 Java 插件。 */
        JAVA_PLUGIN
    }
}
