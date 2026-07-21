package com.bosco.mine.simulator.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证模拟器配置可以安全持久化为 YAML。
 */
class YamlProfileStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void 应按名称保存并重新加载三种传输配置() throws Exception {
        Path file = temporaryDirectory.resolve("profiles.yaml");
        YamlProfileStore store = new YamlProfileStore(file);
        SimulatorProfile mqtt = profile("mqtt-1", "矿卡 MQTT", TransportMode.MQTT, 8883);
        SimulatorProfile client = profile("tcp-client-1", "车载 TCP", TransportMode.TCP_CLIENT, 9000);
        SimulatorProfile server = profile("tcp-server-1", "PLC TCP", TransportMode.TCP_SERVER, 9001);

        store.save(server);
        store.save(mqtt);
        store.save(client);

        YamlProfileStore reloaded = new YamlProfileStore(file);
        assertThat(reloaded.findAll()).extracting(SimulatorProfile::name)
                .containsExactly("PLC TCP", "车载 TCP", "矿卡 MQTT");
        assertThat(reloaded.findById("mqtt-1")).contains(mqtt);
        assertThat(Files.readString(file)).doesNotContain("password", "secret");
    }

    @Test
    void 应以相同标识更新并支持删除() {
        YamlProfileStore store = new YamlProfileStore(temporaryDirectory.resolve("profiles.yaml"));
        store.save(profile("mqtt-1", "旧名称", TransportMode.MQTT, 1883));

        store.save(profile("mqtt-1", "新名称", TransportMode.MQTT, 8883));

        assertThat(store.findAll()).singleElement().satisfies(profile -> {
            assertThat(profile.name()).isEqualTo("新名称");
            assertThat(profile.port()).isEqualTo(8883);
        });
        assertThat(store.delete("mqtt-1")).isTrue();
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void 应拒绝损坏的YAML与非法端口() throws Exception {
        Path file = temporaryDirectory.resolve("profiles.yaml");
        Files.writeString(file, "profiles: [");

        assertThatThrownBy(() -> new YamlProfileStore(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法读取");
        assertThatThrownBy(() -> profile("bad", "非法配置", TransportMode.TCP_CLIENT, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");
    }

    @Test
    void 写入或删除失败时应回滚内存状态() throws Exception {
        Path blockedParent = temporaryDirectory.resolve("即将阻塞的目录");
        Files.createDirectory(blockedParent);
        YamlProfileStore cannotWrite = new YamlProfileStore(blockedParent.resolve("profiles.yaml"));
        Files.delete(blockedParent);
        Files.writeString(blockedParent, "not-a-directory");

        assertThatThrownBy(() -> cannotWrite.save(profile("new", "无法保存", TransportMode.TCP_CLIENT, 9000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法保存");
        assertThat(cannotWrite.findAll()).isEmpty();

        Path file = temporaryDirectory.resolve("profiles.yaml");
        YamlProfileStore store = new YamlProfileStore(file);
        SimulatorProfile existing = profile("existing", "已有配置", TransportMode.TCP_CLIENT, 9001);
        store.save(existing);
        Files.delete(file);
        Files.createDirectory(file);

        assertThatThrownBy(() -> store.delete(existing.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法保存");
        assertThat(store.findById(existing.id())).contains(existing);
    }

    private SimulatorProfile profile(String id, String name, TransportMode mode, int port) {
        return new SimulatorProfile(
                id, name, mode, "127.0.0.1", port, port != 1883,
                "haul-truck", "simulator-001", "device-client", "device-user",
                "mine/v1/haul-truck/device-client/up/telemetry", PayloadFormat.JSON,
                "{\"sequence\":${sequence},\"occurredAt\":\"${timestamp}\"}", 1000, 1
        );
    }
}
