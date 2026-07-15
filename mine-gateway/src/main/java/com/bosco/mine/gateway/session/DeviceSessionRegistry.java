package com.bosco.mine.gateway.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 保存 gateway 当前生效的 MQTT 凭据身份快照。
 *
 * <p>RabbitMQ 只暴露主题中的客户端标识，不能证明报文内业务设备编码。该注册表负责把平台签发的
 * {@code productKey + clientId} 映射到可信业务设备，快照替换不会让消费线程看到半更新状态。</p>
 */
public final class DeviceSessionRegistry {

    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AtomicReference<Map<IdentityKey, DeviceIdentity>> snapshot =
            new AtomicReference<>(Map.of());

    /**
     * 原子替换全部已签发设备身份。
     *
     * @param identities 新身份快照
     */
    public void replace(List<DeviceIdentity> identities) {
        Objects.requireNonNull(identities, "identities 不能为空");
        Map<IdentityKey, DeviceIdentity> next = new LinkedHashMap<>();
        for (DeviceIdentity identity : identities) {
            Objects.requireNonNull(identity, "设备身份不能为空");
            IdentityKey key = new IdentityKey(identity.productKey(), identity.clientId());
            if (next.putIfAbsent(key, identity) != null) {
                throw new IllegalArgumentException("MQTT 设备身份重复: " + key);
            }
        }
        snapshot.set(Map.copyOf(next));
    }

    /**
     * 查询主题身份对应的可信业务设备。
     *
     * @param productKey 产品标识
     * @param clientId MQTT 客户端标识
     * @return 已登记身份，不存在时为空
     */
    public Optional<DeviceIdentity> find(String productKey, String clientId) {
        return Optional.ofNullable(snapshot.get().get(new IdentityKey(productKey, clientId)));
    }

    private record IdentityKey(String productKey, String clientId) {
        private IdentityKey {
            productKey = requireTopicSegment(productKey, "productKey");
            clientId = requireTopicSegment(clientId, "clientId");
        }
    }

    /**
     * 描述一个经平台签发并允许接入的设备身份。
     *
     * @param productKey 产品标识
     * @param clientId MQTT 用户名及客户端标识
     * @param deviceId 平台内部使用的业务设备标识
     * @param deviceCode 厂商报文和矿区台账使用的设备自然编码
     * @param tenantScope 矿区隔离范围
     * @param protocolVersion 当前解析协议版本
     */
    public record DeviceIdentity(
            String productKey,
            String clientId,
            String deviceId,
            String deviceCode,
            String tenantScope,
            String protocolVersion
    ) {
        /**
         * 创建并校验设备身份。
         */
        public DeviceIdentity {
            productKey = requireTopicSegment(productKey, "productKey");
            clientId = requireTopicSegment(clientId, "clientId");
            deviceId = requireUuid(deviceId);
            deviceCode = requireText(deviceCode, "deviceCode");
            tenantScope = requireText(tenantScope, "tenantScope");
            protocolVersion = requireText(protocolVersion, "protocolVersion");
        }
    }

    private static String requireTopicSegment(String value, String field) {
        if (value == null || !TOPIC_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 只能包含字母、数字、下划线和连字符，长度为 1 到 64");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(requireText(value, "deviceId")).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("deviceId 必须是 UUID", exception);
        }
    }
}
