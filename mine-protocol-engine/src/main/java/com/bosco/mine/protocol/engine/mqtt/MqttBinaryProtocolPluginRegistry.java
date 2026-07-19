package com.bosco.mine.protocol.engine.mqtt;

import com.bosco.mine.protocol.engine.ProtocolValidationException;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 原子发现并校验当前进程可用的 MQTT 二进制协议插件。
 */
public final class MqttBinaryProtocolPluginRegistry {

    private static final Pattern PROTOCOL_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern PROTOCOL_VERSION = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final Map<String, MqttBinaryProtocolPlugin> plugins;

    /**
     * 校验并注册显式提供的完整插件集合。
     *
     * @param candidates 候选插件集合
     */
    public MqttBinaryProtocolPluginRegistry(
            Iterable<? extends MqttBinaryProtocolPlugin> candidates
    ) {
        if (candidates == null) {
            throw invalid("MQTT_BINARY_PLUGIN_INVALID", "MQTT 二进制插件集合不能为空");
        }
        Map<String, MqttBinaryProtocolPlugin> validated = new LinkedHashMap<>();
        try {
            for (MqttBinaryProtocolPlugin plugin : candidates) {
                validateAndAdd(validated, plugin);
            }
        } catch (ProtocolValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("MQTT_BINARY_PLUGIN_INVALID", "MQTT 二进制插件元数据读取失败");
        }
        this.plugins = Collections.unmodifiableMap(new LinkedHashMap<>(validated));
    }

    /**
     * 使用标准 Java SPI 发现并原子注册插件。
     *
     * @param classLoader 加载离线审核插件的类加载器
     * @return 完整校验后的不可变注册表
     */
    public static MqttBinaryProtocolPluginRegistry load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "插件类加载器不能为空");
        List<MqttBinaryProtocolPlugin> discovered = new ArrayList<>();
        try {
            ServiceLoader.load(MqttBinaryProtocolPlugin.class, classLoader)
                    .forEach(discovered::add);
        } catch (ServiceConfigurationError | RuntimeException exception) {
            throw invalid("MQTT_BINARY_PLUGIN_LOAD_FAILED", "MQTT 二进制插件 SPI 加载失败");
        }
        return new MqttBinaryProtocolPluginRegistry(discovered);
    }

    /**
     * 按协议键查询插件。
     *
     * @param protocolKey 协议键
     * @return 已安装插件，不存在时为空
     */
    public Optional<MqttBinaryProtocolPlugin> find(String protocolKey) {
        if (protocolKey == null) return Optional.empty();
        return Optional.ofNullable(plugins.get(protocolKey));
    }

    /**
     * 获取产品配置要求的已安装插件。
     *
     * @param protocolKey 协议键
     * @return 已安装插件
     */
    public MqttBinaryProtocolPlugin require(String protocolKey) {
        return find(protocolKey).orElseThrow(() -> invalid(
                "MQTT_BINARY_PLUGIN_NOT_FOUND", "MQTT 二进制插件未安装: " + protocolKey));
    }

    /** @return 按发现顺序排列的只读协议键集合 */
    public Set<String> protocolKeys() {
        return plugins.keySet();
    }

    private void validateAndAdd(
            Map<String, MqttBinaryProtocolPlugin> target,
            MqttBinaryProtocolPlugin plugin
    ) {
        if (plugin == null) {
            throw invalid("MQTT_BINARY_PLUGIN_INVALID", "MQTT 二进制插件不能为空");
        }
        String key = plugin.protocolKey();
        if (key == null || !PROTOCOL_KEY.matcher(key).matches()) {
            throw invalid("MQTT_BINARY_PLUGIN_KEY_INVALID", "MQTT 二进制协议键格式不合法");
        }
        String version = plugin.protocolVersion();
        if (version == null || !PROTOCOL_VERSION.matcher(version).matches()) {
            throw invalid("MQTT_BINARY_PLUGIN_VERSION_INVALID", "MQTT 二进制协议版本格式不合法: " + key);
        }
        if (plugin.apiMajorVersion() != MqttBinaryProtocolPlugin.API_MAJOR_VERSION) {
            throw invalid("MQTT_BINARY_PLUGIN_API_INCOMPATIBLE", "MQTT 二进制插件 API 主版本不兼容: " + key);
        }
        if (target.putIfAbsent(key, plugin) != null) {
            throw invalid("MQTT_BINARY_PLUGIN_DUPLICATE_KEY", "MQTT 二进制协议键重复: " + key);
        }
    }

    private static ProtocolValidationException invalid(String code, String message) {
        return new ProtocolValidationException(code, message);
    }
}
