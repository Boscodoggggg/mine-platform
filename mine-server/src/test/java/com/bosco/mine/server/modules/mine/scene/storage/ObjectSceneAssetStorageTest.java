package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验场景资源通过通用对象存储完成内容寻址发布和范围读取。
 */
class ObjectSceneAssetStorageTest {

    @TempDir
    private Path temporaryDirectory;

    /** 健康探针应使用专用对象键并在读取后删除。 */
    @Test
    void 健康探针应创建读取并删除专用对象() throws Exception {
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage();
        objectStorage.objects.put("scene/business/object.glb", new byte[]{1});
        objectStorage.contentTypes.put("scene/business/object.glb", "model/gltf-binary");
        ObjectSceneAssetStorage storage = storage(objectStorage);

        storage.probe();

        assertThat(objectStorage.objects)
                .containsOnlyKeys("scene/business/object.glb");
        assertThat(objectStorage.deletedKeys).singleElement()
                .asString().startsWith("scene/.health/");
    }

    /** 合法 GLB 应上传到稳定对象键并清理本地 staging。 */
    @Test
    void 应发布内容寻址对象并清理暂存目录() throws Exception {
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage();
        ObjectSceneAssetStorage storage = storage(objectStorage);

        SceneAssetStorage.StoredAsset stored = storage.store(
                new ByteArrayInputStream(minimalGlb()), "truck.glb", "model/gltf-binary");

        String key = "scene/" + stored.contentDigest().substring("sha256:".length())
                + "/model.glb";
        assertThat(objectStorage.objects).containsKey(key);
        assertThat(objectStorage.objects.get(key)).isEqualTo(minimalGlb());
        try (var paths = Files.list(temporaryDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    /** 范围读取应映射为标准对象存储 Range。 */
    @Test
    void 应通过标准对象存储范围读取() throws Exception {
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage();
        ObjectSceneAssetStorage storage = storage(objectStorage);
        SceneAssetStorage.StoredAsset stored = storage.store(
                new ByteArrayInputStream(minimalGlb()), "truck.glb", "model/gltf-binary");

        try (SceneAssetStorage.StoredContent content = storage.open(
                stored.contentDigest(), "model.glb", new SceneAssetStorage.ByteRange(1, 4))) {
            assertThat(content.inputStream().readAllBytes())
                    .isEqualTo(Arrays.copyOfRange(minimalGlb(), 1, 5));
            assertThat(content.contentLength()).isEqualTo(4);
            assertThat(content.contentRange()).isEqualTo(
                    "bytes 1-4/" + minimalGlb().length);
        }
    }

    private ObjectSceneAssetStorage storage(ObjectStorage objectStorage) {
        SceneAssetStorageProperties properties = new SceneAssetStorageProperties(
                temporaryDirectory, 100000, 100000, 100000,
                100, 100, 10000, Duration.ofHours(1));
        return new ObjectSceneAssetStorage(
                properties,
                new SceneAssetPackageValidator(properties, JsonMapper.builder().build()),
                objectStorage);
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

    /** 仅用于契约测试的内存对象存储。 */
    private static final class InMemoryObjectStorage implements ObjectStorage {

        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, String> contentTypes = new HashMap<>();
        private final java.util.List<String> deletedKeys = new java.util.ArrayList<>();

        @Override
        public void put(String objectKey, InputStream source, long contentLength, String contentType) {
            try {
                byte[] content = source.readAllBytes();
                if (content.length != contentLength) {
                    throw new IllegalArgumentException("测试对象长度不一致");
                }
                objects.put(objectKey, content);
                contentTypes.put(objectKey, contentType);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public ObjectMetadata head(String objectKey) {
            byte[] content = objects.get(objectKey);
            return new ObjectMetadata(content.length, contentTypes.get(objectKey), "etag");
        }

        @Override
        public ObjectContent get(String objectKey, ObjectStorage.ByteRange range) {
            byte[] source = objects.get(objectKey);
            int start = range == null ? 0 : Math.toIntExact(range.startInclusive());
            int end = range == null ? source.length - 1 : Math.toIntExact(range.endInclusive());
            byte[] selected = Arrays.copyOfRange(source, start, end + 1);
            return new ObjectContent(
                    new ByteArrayInputStream(selected),
                    new ObjectMetadata(selected.length, contentTypes.get(objectKey), "etag"),
                    range == null ? null : "bytes " + start + "-" + end + "/" + source.length);
        }

        @Override
        public void delete(String objectKey) {
            deletedKeys.add(objectKey);
            objects.remove(objectKey);
            contentTypes.remove(objectKey);
        }
    }
}
