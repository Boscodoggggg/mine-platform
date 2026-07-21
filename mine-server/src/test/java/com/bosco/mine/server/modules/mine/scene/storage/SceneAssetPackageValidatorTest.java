package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * 校验三维资源格式、解压边界和包内引用安全策略。
 */
class SceneAssetPackageValidatorTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void 应接受最小Glb并输出固定入口和文件摘要() throws Exception {
        Path source = write("valid.glb", minimalGlb());
        Path output = temporaryDirectory.resolve("valid-glb");

        SceneAssetPackageValidator.ValidatedPackage result = validator(properties())
                .validate(source, SceneAssetPolicy.SceneAssetType.GLB, output);

        assertThat(result.entryPath()).isEqualTo("model.glb");
        assertThat(result.expandedSizeBytes()).isEqualTo(minimalGlb().length);
        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.relativePath()).isEqualTo("model.glb");
            assertThat(file.contentType()).isEqualTo("model/gltf-binary");
            assertThat(file.contentDigest()).matches("sha256:[0-9a-f]{64}");
        });
        assertThat(Files.readAllBytes(output.resolve("model.glb"))).isEqualTo(minimalGlb());
    }

    @Test
    void 应拒绝Glb错误魔数版本声明长度和文件上限() throws Exception {
        byte[] invalidMagic = minimalGlb();
        invalidMagic[0] = 0;
        byte[] invalidVersion = minimalGlb();
        littleEndian(invalidVersion).putInt(4, 1);
        byte[] invalidLength = minimalGlb();
        littleEndian(invalidLength).putInt(8, invalidLength.length + 1);
        byte[] externalUri = glb("{\"images\":[{\"uri\":\"https://example.com/a.png\"}]}");

        assertInvalidGlb("magic.glb", invalidMagic, properties());
        assertInvalidGlb("version.glb", invalidVersion, properties());
        assertInvalidGlb("length.glb", invalidLength, properties());
        assertInvalidGlb("external-uri.glb", externalUri, properties());
        assertInvalidGlb("oversize.glb", minimalGlb(), properties(
                1024, 1024, minimalGlb().length - 1, 10, 100, 16));
    }

    @Test
    void 应接受包内引用完整的最小三维瓦片包() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("tileset.json", tileset("models/tile.glb"));
        entries.put("models/tile.glb", minimalGlb());
        Path source = write("valid.zip", zip(entries));
        Path output = temporaryDirectory.resolve("valid-tiles");

        SceneAssetPackageValidator.ValidatedPackage result = validator(properties())
                .validate(source, SceneAssetPolicy.SceneAssetType.TILES_3D, output);

        assertThat(result.entryPath()).isEqualTo("tileset.json");
        assertThat(result.files()).extracting(
                SceneAssetPackageValidator.ValidatedFile::relativePath)
                .containsExactly("models/tile.glb", "tileset.json");
        assertThat(Files.exists(output.resolve("models/tile.glb"))).isTrue();
    }

    @Test
    void 应拒绝路径穿越绝对路径反斜杠和大小写冲突() throws Exception {
        assertInvalidZip("traversal.zip", entries(
                "tileset.json", tileset("../escape.glb"),
                "../escape.glb", minimalGlb()), properties());
        assertInvalidZip("absolute.zip", entries(
                "tileset.json", tileset("/model.glb"),
                "/model.glb", minimalGlb()), properties());
        assertInvalidZip("windows.zip", entries(
                "tileset.json", tileset("models\\tile.glb"),
                "models\\tile.glb", minimalGlb()), properties());
        assertInvalidZip("case.zip", entries(
                "tileset.json", tileset("Models/tile.glb"),
                "Models/tile.glb", minimalGlb(),
                "models/tile.glb", minimalGlb()), properties());
    }

    @Test
    void 应拒绝软链接条目且不在输出目录创建链接() throws Exception {
        Map<String, byte[]> entries = entries(
                "tileset.json", tileset("models/tile.glb"),
                "models/tile.glb", minimalGlb());
        byte[] archive = zip(entries);
        markUnixSymlink(archive, "models/tile.glb");
        Path source = write("symlink.zip", archive);
        Path output = temporaryDirectory.resolve("symlink-output");

        assertThatThrownBy(() -> validator(properties()).validate(
                source, SceneAssetPolicy.SceneAssetType.TILES_3D, output))
                .isInstanceOf(SceneAssetPackageValidator.ValidationException.class);
        assertThat(Files.exists(output)).isFalse();
    }

    @Test
    void 应拒绝超文件数超单文件超展开量和高压缩比() throws Exception {
        Map<String, byte[]> threeFiles = entries(
                "tileset.json", tileset("models/a.glb"),
                "models/a.glb", minimalGlb(),
                "models/b.glb", minimalGlb());
        assertInvalidZip("files.zip", threeFiles,
                properties(100000, 100000, 100000, 2, 100, 10000));

        Map<String, byte[]> largeFile = entries(
                "tileset.json", tileset("models/tile.bin"),
                "models/tile.bin", new byte[4096]);
        assertInvalidZip("single.zip", largeFile,
                properties(100000, 100000, 1024, 10, 100, 512));
        Map<String, byte[]> expandedFiles = entries(
                "tileset.json", tileset("models/a.bin"),
                "models/a.bin", new byte[1800],
                "models/b.bin", new byte[1800]);
        assertInvalidZip("expanded.zip", expandedFiles,
                properties(100000, 3000, 2000, 10, 100, 512));
        assertInvalidZip("ratio.zip", largeFile,
                properties(100000, 100000, 10000, 10, 2, 512));
    }

    @Test
    void 应拒绝超限Json外部Uri编码Uri和不存在子资源() throws Exception {
        assertInvalidZip("json-size.zip", entries(
                "tileset.json", tileset("models/tile.glb"),
                "models/tile.glb", minimalGlb()),
                properties(100000, 100000, 100000, 10, 100, 16));
        assertInvalidZip("external.zip", entries(
                "tileset.json", tileset("https://example.com/tile.glb")), properties());
        assertInvalidZip("protocol-relative.zip", entries(
                "tileset.json", tileset("//example.com/tile.glb")), properties());
        assertInvalidZip("encoded.zip", entries(
                "tileset.json", tileset("models/%2e%2e/secret.glb")), properties());
        assertInvalidZip("missing.zip", entries(
                "tileset.json", tileset("models/missing.glb")), properties());
    }

    @Test
    void 应拒绝旧版Url和元数据SchemaUri引用外部资源() throws Exception {
        assertInvalidZip("legacy-external-url.zip", entries(
                "tileset.json", tilesetWithExternalReference(
                        "\"content\":{\"url\":\"https://attacker.example/tile.glb\"}")),
                properties());
        assertInvalidZip("external-schema-uri.zip", entries(
                "tileset.json", tilesetWithSchemaUri(
                        "https://attacker.example/schema.json")),
                properties());
    }

    @Test
    void 非法存储边界配置必须立即失败() {
        assertThatThrownBy(() -> properties(0, 100, 100, 1, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(100, 99, 100, 1, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(100, 100, 101, 1, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SceneAssetStorageProperties(
                temporaryDirectory, 100, 100, 100, 1, 10, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalidGlb(
            String fileName,
            byte[] content,
            SceneAssetStorageProperties properties
    ) throws Exception {
        Path source = write(fileName, content);
        Path output = temporaryDirectory.resolve(fileName + "-output");
        assertThatThrownBy(() -> validator(properties).validate(
                source, SceneAssetPolicy.SceneAssetType.GLB, output))
                .isInstanceOf(SceneAssetPackageValidator.ValidationException.class);
        assertThat(Files.exists(output)).isFalse();
    }

    private void assertInvalidZip(
            String fileName,
            Map<String, byte[]> entries,
            SceneAssetStorageProperties properties
    ) throws Exception {
        Path source = write(fileName, zip(entries));
        Path output = temporaryDirectory.resolve(fileName + "-output");
        assertThatThrownBy(() -> validator(properties).validate(
                source, SceneAssetPolicy.SceneAssetType.TILES_3D, output))
                .isInstanceOf(SceneAssetPackageValidator.ValidationException.class);
        assertThat(Files.exists(output)).isFalse();
    }

    private SceneAssetPackageValidator validator(SceneAssetStorageProperties properties) {
        return new SceneAssetPackageValidator(properties, JsonMapper.builder().build());
    }

    private SceneAssetStorageProperties properties() {
        return properties(100000, 100000, 100000, 100, 100, 10000);
    }

    private SceneAssetStorageProperties properties(
            long maxUploadBytes,
            long maxExpandedBytes,
            long maxSingleFileBytes,
            int maxFileCount,
            double maxCompressionRatio,
            long maxJsonBytes
    ) {
        return new SceneAssetStorageProperties(
                temporaryDirectory.resolve("storage"),
                maxUploadBytes,
                maxExpandedBytes,
                maxSingleFileBytes,
                maxFileCount,
                maxCompressionRatio,
                maxJsonBytes,
                Duration.ofHours(1));
    }

    private Path write(String fileName, byte[] content) throws IOException {
        Path target = temporaryDirectory.resolve(fileName);
        Files.write(target, content);
        return target;
    }

    private byte[] minimalGlb() {
        return glb("{}");
    }

    private byte[] glb(String jsonText) {
        int padding = (4 - jsonText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length % 4) % 4;
        byte[] json = (jsonText + " ".repeat(padding))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private byte[] tileset(String uri) {
        return ("""
                {"asset":{"version":"1.1"},"root":{
                  "boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]},
                  "geometricError":0,"content":{"uri":"%s"}
                }}
                """.formatted(uri)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] tilesetWithExternalReference(String rootProperty) {
        return ("""
                {"asset":{"version":"1.1"},"root":{
                  "boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]},
                  "geometricError":0,%s
                }}
                """.formatted(rootProperty)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] tilesetWithSchemaUri(String schemaUri) {
        return ("""
                {"asset":{"version":"1.1"},
                 "schemaUri":"%s",
                 "root":{"boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]},
                         "geometricError":0}}
                """.formatted(schemaUri)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, byte[]> entries(Object... values) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            entries.put((String) values[index], (byte[]) values[index + 1]);
        }
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries) throws IOException {
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

    private void markUnixSymlink(byte[] archive, String targetName) {
        byte[] target = targetName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index <= archive.length - 46; index++) {
            if (buffer.getInt(index) != 0x02014B50) {
                continue;
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(index + 28));
            if (index + 46 + nameLength > archive.length) {
                break;
            }
            byte[] name = new byte[nameLength];
            System.arraycopy(archive, index + 46, name, 0, nameLength);
            if (java.util.Arrays.equals(name, target)) {
                archive[index + 5] = 3;
                buffer.putInt(index + 38, 0120777 << 16);
                return;
            }
        }
        throw new IllegalArgumentException("未找到待标记的 ZIP 条目");
    }

    private ByteBuffer littleEndian(byte[] content) {
        return ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
    }
}
