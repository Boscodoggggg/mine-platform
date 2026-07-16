package com.bosco.mine.gateway.configuration;

import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 Gateway 最后可用配置快照能够安全持久化并在进程重启后恢复。
 */
class ConfigurationSnapshotCacheTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private final ProtocolSnapshotHasher hasher = new ProtocolSnapshotHasher();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @TempDir
    private Path tempDirectory;

    @Test
    void 持久化成功后新注册表应能恢复最后可用配置() {
        Path cacheFile = tempDirectory.resolve("protocol-snapshots.json");
        ConfigurationSnapshot snapshot = snapshot(7);
        ConfigurationSnapshotCache firstProcess = new ConfigurationSnapshotCache(cacheFile, 1024 * 1024);
        firstProcess.persist(snapshot);

        ProtocolSnapshotRegistry restartedRegistry = new ProtocolSnapshotRegistry(true);
        ConfigurationSnapshotCache restartedProcess = new ConfigurationSnapshotCache(cacheFile, 1024 * 1024);

        assertThat(restartedProcess.restoreInto(restartedRegistry)).isEqualTo(1);
        assertThat(restartedRegistry.find("haul-truck").orElseThrow().releaseId())
                .isEqualTo(snapshot.releaseId());
        assertThat(Files.exists(cacheFile)).isTrue();
    }

    @Test
    void 损坏缓存必须阻止带着未知配置静默启动() throws Exception {
        Path cacheFile = Files.writeString(
                tempDirectory.resolve("corrupted.json"),
                "{\"schemaVersion\":1,\"snapshots\":["
        );

        assertThatThrownBy(() -> new ConfigurationSnapshotCache(cacheFile, 1024 * 1024))
                .isInstanceOf(ConfigurationSnapshotCacheException.class)
                .hasMessageContaining("无法读取");
    }

    @Test
    void 超过容量上限的缓存必须在反序列化前拒绝() throws Exception {
        Path cacheFile = Files.writeString(
                tempDirectory.resolve("oversized.json"),
                "x".repeat(2048)
        );

        assertThatThrownBy(() -> new ConfigurationSnapshotCache(cacheFile, 1024))
                .isInstanceOf(ConfigurationSnapshotCacheException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void 重投应幂等且低序号或同序号异配置不得覆盖缓存() {
        Path cacheFile = tempDirectory.resolve("monotonic.json");
        ConfigurationSnapshotCache cache = new ConfigurationSnapshotCache(cacheFile, 1024 * 1024);
        ConfigurationSnapshot current = snapshot(7);
        cache.persist(current);

        cache.persist(current);

        assertThat(cache.size()).isEqualTo(1);
        assertThatThrownBy(() -> cache.persist(snapshot(6)))
                .isInstanceOf(ConfigurationSnapshotCacheException.class)
                .hasMessageContaining("低发布序号");
        assertThatThrownBy(() -> cache.persist(snapshot(7)))
                .isInstanceOf(ConfigurationSnapshotCacheException.class)
                .hasMessageContaining("相同发布序号");
    }

    private ConfigurationSnapshot snapshot(long sequence) {
        String snapshotJson = validSnapshot();
        return new ConfigurationSnapshot(
                UUID.randomUUID(), PRODUCT_ID, UUID.randomUUID(), sequence,
                hasher.sha256(snapshotJson), snapshotJson
        );
    }

    private String validSnapshot() {
        try {
            return jsonMapper.writeValueAsString(jsonMapper.readTree("""
                    {
                      "schemaVersion":1,
                      "productId":"%s",
                      "productKey":"haul-truck",
                      "modelVersionId":"%s",
                      "protocolMappingVersionId":"%s",
                      "definition":{
                        "protocolKey":"haul-truck-json",
                        "version":2,
                        "metadata":{
                          "productKeyPath":"productKey",
                          "deviceCodePath":"deviceCode",
                          "bootIdPath":"bootId",
                          "sequencePath":"sequence",
                          "occurredAtPath":"occurredAt",
                          "replayedPath":"replayed"
                        },
                        "properties":[{"identifier":"engineRpm","dataType":"INTEGER"}],
                        "fields":[{
                          "sourcePath":"rpm",
                          "sourceType":"INTEGER",
                          "targetIdentifier":"engineRpm",
                          "transform":"NONE",
                          "scaleFactor":null,
                          "offsetValue":null,
                          "enumMapping":{},
                          "requiredField":true
                        }]
                      }
                    }
                    """.formatted(PRODUCT_ID, UUID.randomUUID(), UUID.randomUUID())));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
