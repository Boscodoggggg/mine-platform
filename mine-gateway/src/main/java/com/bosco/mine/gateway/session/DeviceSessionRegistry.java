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
 * 保存 gateway 当前生效的 MQTT 与 TCP 可信设备身份快照。
 *
 * <p>RabbitMQ 只暴露主题中的客户端标识，不能证明报文内业务设备编码。该注册表负责把平台签发的
 * {@code productKey + clientId} 或 {@code productKey + deviceCode} 映射到可信业务设备，快照替换不会让
 * 消费线程看到半更新状态。</p>
 */
public final class DeviceSessionRegistry {

    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    private final AtomicReference<IdentitySnapshot> snapshot =
            new AtomicReference<>(new IdentitySnapshot(
                    Map.of(), Map.of(), Map.of(), new SnapshotStatus(0, "", 0)
            ));

    /**
     * 校验候选身份并按单调版本规则原子激活完整快照。
     *
     * <p>低版本和同版本异摘要会被拒绝；同版本同摘要属于幂等重复，不会替换当前索引。</p>
     *
     * @param version 候选快照版本，必须大于等于 1
     * @param sha256 候选原始配置的小写 SHA-256
     * @param identities 候选快照的全部设备身份
     * @return 实际激活或保持不变
     */
    public ActivationResult activate(long version, String sha256, List<DeviceIdentity> identities) {
        if (version < 1) {
            throw new IllegalArgumentException("设备身份快照版本必须大于等于 1");
        }
        if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("设备身份快照 SHA-256 必须是 64 位小写十六进制");
        }

        IdentityIndexes indexes = indexes(identities);
        SnapshotStatus candidateStatus = new SnapshotStatus(version, sha256, indexes.byTopic().size());
        IdentitySnapshot candidate = new IdentitySnapshot(
                indexes.byTopic(), indexes.byTcpIdentity(), indexes.byDeviceId(), candidateStatus
        );
        while (true) {
            IdentitySnapshot current = snapshot.get();
            if (version < current.status().version()) {
                throw new IllegalArgumentException(
                        "设备身份快照版本 %d 低于当前版本 %d".formatted(version, current.status().version())
                );
            }
            if (version == current.status().version()) {
                if (sha256.equals(current.status().sha256())) {
                    return ActivationResult.UNCHANGED;
                }
                throw new IllegalArgumentException("设备身份快照相同版本对应不同 SHA-256");
            }
            if (snapshot.compareAndSet(current, candidate)) {
                return ActivationResult.ACTIVATED;
            }
        }
    }

    private IdentityIndexes indexes(List<DeviceIdentity> identities) {
        Objects.requireNonNull(identities, "identities 不能为空");
        Map<IdentityKey, DeviceIdentity> byTopic = new LinkedHashMap<>();
        Map<TcpIdentityKey, DeviceIdentity> byTcpIdentity = new LinkedHashMap<>();
        Map<UUID, DeviceIdentity> byDeviceId = new LinkedHashMap<>();
        for (DeviceIdentity identity : identities) {
            Objects.requireNonNull(identity, "设备身份不能为空");
            IdentityKey key = new IdentityKey(identity.productKey(), identity.clientId());
            if (byTopic.putIfAbsent(key, identity) != null) {
                throw new IllegalArgumentException("MQTT 设备身份重复: " + key);
            }
            TcpIdentityKey tcpKey = new TcpIdentityKey(identity.productKey(), identity.deviceCode());
            if (byTcpIdentity.putIfAbsent(tcpKey, identity) != null) {
                throw new IllegalArgumentException("TCP 设备身份重复: " + tcpKey);
            }
            UUID deviceId = UUID.fromString(identity.deviceId());
            if (byDeviceId.putIfAbsent(deviceId, identity) != null) {
                throw new IllegalArgumentException("业务设备身份重复: " + deviceId);
            }
        }
        return new IdentityIndexes(
                Map.copyOf(byTopic), Map.copyOf(byTcpIdentity), Map.copyOf(byDeviceId));
    }

    /**
     * 查询主题身份对应的可信业务设备。
     *
     * @param productKey 产品标识
     * @param clientId MQTT 客户端标识
     * @return 已登记身份，不存在时为空
     */
    public Optional<DeviceIdentity> find(String productKey, String clientId) {
        return Optional.ofNullable(snapshot.get().byTopic().get(new IdentityKey(productKey, clientId)));
    }

    /**
     * 按 TCP 帧内产品编码和设备自然编码查询平台可信身份。
     *
     * @param productKey 产品标识
     * @param deviceCode 设备自然编码
     * @return 已登记身份，不存在时为空
     */
    public Optional<DeviceIdentity> findByDeviceCode(String productKey, String deviceCode) {
        return Optional.ofNullable(snapshot.get().byTcpIdentity().get(
                new TcpIdentityKey(productKey, deviceCode)));
    }

    /**
     * 查询平台设备标识对应的 MQTT 下行身份。
     *
     * @param deviceId 平台设备标识
     * @return 已登记身份，不存在时为空
     */
    public Optional<DeviceIdentity> findByDeviceId(UUID deviceId) {
        return Optional.ofNullable(snapshot.get().byDeviceId().get(
                Objects.requireNonNull(deviceId, "deviceId 不能为空")
        ));
    }

    /**
     * 返回与当前身份索引同一次原子激活的版本状态。
     *
     * @return 当前生效版本、摘要和设备数量；尚未激活时版本为 0
     */
    public SnapshotStatus status() {
        return snapshot.get().status();
    }

    /**
     * 把身份索引和发布元数据绑定为一次不可分割的运行时快照。
     */
    private record IdentitySnapshot(
            Map<IdentityKey, DeviceIdentity> byTopic,
            Map<TcpIdentityKey, DeviceIdentity> byTcpIdentity,
            Map<UUID, DeviceIdentity> byDeviceId,
            SnapshotStatus status
    ) {
    }

    /**
     * 保存候选快照完成唯一性校验后生成的双向身份索引。
     */
    private record IdentityIndexes(
            Map<IdentityKey, DeviceIdentity> byTopic,
            Map<TcpIdentityKey, DeviceIdentity> byTcpIdentity,
            Map<UUID, DeviceIdentity> byDeviceId
    ) {
    }

    private record IdentityKey(String productKey, String clientId) {
        private IdentityKey {
            productKey = requireTopicSegment(productKey, "productKey");
            clientId = requireTopicSegment(clientId, "clientId");
        }
    }

    private record TcpIdentityKey(String productKey, String deviceCode) {
        private TcpIdentityKey {
            productKey = requireTopicSegment(productKey, "productKey");
            deviceCode = requireText(deviceCode, "deviceCode");
        }
    }

    /**
     * 描述一次版本化身份快照激活的结果。
     */
    public enum ActivationResult {
        /** 候选快照已成为当前生效快照。 */
        ACTIVATED,
        /** 候选版本和摘要与当前快照相同，注册表保持不变。 */
        UNCHANGED
    }

    /**
     * 描述当前生效身份快照的可审计状态。
     *
     * @param version 生效版本；0 表示尚未加载版本化快照
     * @param sha256 生效原始配置的小写 SHA-256；未加载时为空
     * @param deviceCount 当前快照包含的设备数量
     */
    public record SnapshotStatus(long version, String sha256, int deviceCount) {
        /**
         * 创建不可变快照状态。
         */
        public SnapshotStatus {
            if (version < 0) throw new IllegalArgumentException("快照版本不能为负数");
            if (deviceCount < 0) throw new IllegalArgumentException("设备数量不能为负数");
            if (version == 0 && (sha256 == null || !sha256.isEmpty())) {
                throw new IllegalArgumentException("未激活快照的 SHA-256 必须为空");
            }
            if (version > 0 && (sha256 == null || !SHA_256.matcher(sha256).matches())) {
                throw new IllegalArgumentException("已激活快照的 SHA-256 格式不合法");
            }
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
