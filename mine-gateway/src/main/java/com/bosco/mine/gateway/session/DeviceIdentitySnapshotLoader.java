package com.bosco.mine.gateway.session;

import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从版本化 JSON 文件加载设备凭据身份快照，并在文件更新后原子替换运行时注册表。
 *
 * <p>文件是阶段 1 的启动与现场兜底通道；后续前台发布的配置事件复用注册表原子替换接口。</p>
 */
public final class DeviceIdentitySnapshotLoader {

    private final DeviceSessionRegistry registry;
    private final Path snapshotFile;
    private final JsonMapper jsonMapper;

    /**
     * 创建设备身份快照加载器。
     *
     * @param registry 运行时身份注册表
     * @param snapshotFile 版本化 JSON 快照文件
     */
    public DeviceIdentitySnapshotLoader(DeviceSessionRegistry registry, Path snapshotFile) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.snapshotFile = Objects.requireNonNull(snapshotFile, "snapshotFile 不能为空");
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * 重新读取完整快照。解析或校验失败时不会修改当前生效快照。
     */
    @Scheduled(fixedDelayString = "${mine.gateway.rabbit.identity-refresh-millis:5000}")
    public void reload() {
        JsonNode root = read();
        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() < 1) {
            throw new IllegalArgumentException("设备身份快照 version 必须大于等于 1");
        }
        JsonNode devices = root.get("devices");
        if (devices == null || !devices.isArray()) {
            throw new IllegalArgumentException("设备身份快照 devices 必须是数组");
        }
        List<DeviceSessionRegistry.DeviceIdentity> identities = new ArrayList<>();
        for (JsonNode device : devices) {
            if (!device.isObject()) throw new IllegalArgumentException("devices 元素必须是对象");
            identities.add(new DeviceSessionRegistry.DeviceIdentity(
                    text(device, "productKey"),
                    text(device, "clientId"),
                    text(device, "deviceId"),
                    text(device, "tenantScope"),
                    text(device, "protocolVersion")
            ));
        }
        registry.replace(identities);
    }

    private JsonNode read() {
        try {
            JsonNode root = jsonMapper.readTree(Files.readAllBytes(snapshotFile));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("设备身份快照根节点必须是对象");
            }
            return root;
        } catch (IOException | JacksonException exception) {
            throw new IllegalArgumentException("无法读取设备身份快照: " + snapshotFile, exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("设备身份字段 " + field + " 不能为空");
        }
        return value.asString();
    }
}
