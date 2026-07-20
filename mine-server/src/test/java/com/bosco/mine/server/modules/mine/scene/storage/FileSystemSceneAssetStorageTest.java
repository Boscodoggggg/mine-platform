package com.bosco.mine.server.modules.mine.scene.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验文件系统三维资源存储的内容寻址、原子发布和失败清理行为。
 */
class FileSystemSceneAssetStorageTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void 应按上传内容摘要原子保存Glb并安全解析清单文件() throws Exception {
        SceneAssetStorage storage = storage(properties(100000));

        SceneAssetStorage.StoredAsset result = storage.store(
                new ByteArrayInputStream(minimalGlb()), "truck.glb", "model/gltf-binary");

        assertThat(result.contentDigest()).matches("sha256:[0-9a-f]{64}");
        assertThat(result.sourceSizeBytes()).isEqualTo(minimalGlb().length);
        assertThat(result.entryPath()).isEqualTo("model.glb");
        try (SceneAssetStorage.StoredContent storedFile = storage.open(
                result.contentDigest(), "model.glb", null)) {
            assertThat(storedFile.inputStream().readAllBytes()).isEqualTo(minimalGlb());
        }
        assertThat(stagingEntries()).isZero();
    }

    @Test
    void 相同上传内容必须幂等复用同一对象目录() throws Exception {
        SceneAssetStorage storage = storage(properties(100000));
        byte[] archive = validTilesArchive();

        SceneAssetStorage.StoredAsset first = storage.store(
                new ByteArrayInputStream(archive), "mine.zip", "application/zip");
        SceneAssetStorage.StoredAsset second = storage.store(
                new ByteArrayInputStream(archive), "mine.zip", "application/zip");

        assertThat(second).isEqualTo(first);
        try (var objects = Files.list(temporaryDirectory.resolve("objects"))) {
            assertThat(objects.filter(Files::isDirectory).count()).isOne();
        }
        assertThat(stagingEntries()).isZero();
    }

    @Test
    void 超上传上限中途读取失败和非法包都不得留下最终对象() throws Exception {
        SceneAssetStorage limited = storage(properties(16));
        assertThatThrownBy(() -> limited.store(
                new ByteArrayInputStream(minimalGlb()), "large.glb", "model/gltf-binary"))
                .isInstanceOf(IOException.class);
        assertNoObjectsOrStaging();

        SceneAssetStorage normal = storage(properties(100000));
        InputStream broken = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index >= 8) {
                    throw new IOException("模拟上传中断");
                }
                return minimalGlb()[index++] & 0xff;
            }
        };
        assertThatThrownBy(() -> normal.store(
                broken, "broken.glb", "model/gltf-binary"))
                .isInstanceOf(IOException.class);
        assertNoObjectsOrStaging();

        assertThatThrownBy(() -> normal.store(
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "invalid.glb", "model/gltf-binary"))
                .isInstanceOf(SceneAssetPackageValidator.ValidationException.class);
        assertNoObjectsOrStaging();
    }

    @Test
    void 读取必须拒绝非法摘要路径穿越和被篡改为软链接的文件() throws Exception {
        SceneAssetStorage storage = storage(properties(100000));
        SceneAssetStorage.StoredAsset result = storage.store(
                new ByteArrayInputStream(minimalGlb()), "truck.glb", "model/gltf-binary");

        assertThatThrownBy(() -> storage.open("sha256:bad", "model.glb", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(result.contentDigest(), "../model.glb", null))
                .isInstanceOf(IllegalArgumentException.class);

        Path stored = temporaryDirectory.resolve("objects")
                .resolve(result.contentDigest().substring("sha256:".length()))
                .resolve("model.glb");
        Path outside = temporaryDirectory.resolve("outside.glb");
        Files.write(outside, minimalGlb());
        Files.delete(stored);
        Files.createSymbolicLink(stored, outside);
        assertThatThrownBy(() -> storage.open(result.contentDigest(), "model.glb", null))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 应返回受限的单范围内容流() throws Exception {
        SceneAssetStorage storage = storage(properties(100000));
        SceneAssetStorage.StoredAsset result = storage.store(
                new ByteArrayInputStream(minimalGlb()), "truck.glb", "model/gltf-binary");

        try (SceneAssetStorage.StoredContent content = storage.open(
                result.contentDigest(), "model.glb", new SceneAssetStorage.ByteRange(0, 3))) {
            assertThat(content.inputStream().readAllBytes())
                    .containsExactly(java.util.Arrays.copyOfRange(minimalGlb(), 0, 4));
            assertThat(content.contentLength()).isEqualTo(4);
        }
    }

    private SceneAssetStorage storage(SceneAssetStorageProperties properties) {
        SceneAssetPackageValidator validator = new SceneAssetPackageValidator(
                properties, JsonMapper.builder().build());
        return new FileSystemSceneAssetStorage(properties, validator);
    }

    private SceneAssetStorageProperties properties(long maxUploadBytes) {
        return new SceneAssetStorageProperties(
                temporaryDirectory,
                maxUploadBytes,
                100000,
                100000,
                100,
                100,
                10000,
                Duration.ofHours(1));
    }

    private long stagingEntries() throws IOException {
        Path staging = temporaryDirectory.resolve(".staging");
        if (Files.notExists(staging)) {
            return 0;
        }
        try (var entries = Files.list(staging)) {
            return entries.count();
        }
    }

    private void assertNoObjectsOrStaging() throws IOException {
        Path objects = temporaryDirectory.resolve("objects");
        if (Files.exists(objects)) {
            try (var entries = Files.list(objects)) {
                assertThat(entries.count()).isZero();
            }
        }
        assertThat(stagingEntries()).isZero();
    }

    private byte[] minimalGlb() {
        byte[] json = "{}  ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(20 + json.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67)
                .putInt(2)
                .putInt(20 + json.length)
                .putInt(json.length)
                .putInt(0x4E4F534A)
                .put(json)
                .array();
    }

    private byte[] validTilesArchive() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("tileset.json", ("""
                {"asset":{"version":"1.1"},"root":{
                  "boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]},
                  "geometricError":0,"content":{"uri":"models/tile.glb"}
                }}
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("models/tile.glb", minimalGlb());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
