package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.infrastructure.storage.ObjectStorage;
import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用通用对象存储发布和读取三维内容寻址资源。
 */
@Component
@EnableConfigurationProperties(SceneAssetStorageProperties.class)
@ConditionalOnProperty(prefix = "mine.scene-storage", name = "type", havingValue = "s3")
public final class ObjectSceneAssetStorage implements SceneAssetStorage {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private final SceneAssetStorageProperties properties;
    private final SceneAssetPackageValidator validator;
    private final ObjectStorage objectStorage;
    private final Path stagingRoot;

    /**
     * 创建基于标准对象存储的场景资源适配器。
     *
     * @param properties 场景上传与暂存安全边界
     * @param validator 三维资源包校验器
     * @param objectStorage 通用对象存储端口
     */
    public ObjectSceneAssetStorage(
            SceneAssetStorageProperties properties,
            SceneAssetPackageValidator validator,
            ObjectStorage objectStorage
    ) {
        this.properties = Objects.requireNonNull(properties, "三维资源存储配置不能为空");
        this.validator = Objects.requireNonNull(validator, "三维资源校验器不能为空");
        this.objectStorage = Objects.requireNonNull(objectStorage, "对象存储端口不能为空");
        this.stagingRoot = properties.root();
        try {
            Files.createDirectories(stagingRoot);
            if (Files.isSymbolicLink(stagingRoot)
                    || !Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("三维资源暂存目录不是安全普通目录");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("三维资源暂存目录不可用", exception);
        }
    }

    @Override
    public StoredAsset store(InputStream source, String fileName, String contentType)
            throws IOException {
        Path workspace = stagingRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectory(workspace);
        SceneAssetStorageProperties workspaceProperties = workspaceProperties(workspace);
        FileSystemSceneAssetStorage local = new FileSystemSceneAssetStorage(
                workspaceProperties, validator);
        try {
            StoredAsset stored = local.store(source, fileName, contentType);
            for (StoredFile file : stored.files()) {
                try (StoredContent content = local.open(
                        stored.contentDigest(), file.relativePath(), null)) {
                    objectStorage.put(
                            objectKey(stored.contentDigest(), file.relativePath()),
                            content.inputStream(), file.sizeBytes(), file.contentType());
                }
            }
            return stored;
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Override
    public StoredContent open(
            String contentDigest,
            String relativePath,
            ByteRange range
    ) throws IOException {
        String key = objectKey(contentDigest, relativePath);
        ObjectStorage.ByteRange objectRange = range == null
                ? null : new ObjectStorage.ByteRange(
                        range.startInclusive(), range.endInclusive());
        try {
            ObjectStorage.ObjectContent content = objectStorage.get(key, objectRange);
            return new StoredContent(
                    content.inputStream(), content.metadata().contentLength(),
                    content.contentRange());
        } catch (RuntimeException exception) {
            throw new IOException("三维资源对象读取失败", exception);
        }
    }

    private String objectKey(String contentDigest, String relativePath) {
        String digest = requireDigest(contentDigest);
        String safePath = SceneAssetPolicy.safeRelativePath(relativePath);
        return "scene/" + digest.substring("sha256:".length()) + "/" + safePath;
    }

    private String requireDigest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException("三维资源摘要格式无效");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private SceneAssetStorageProperties workspaceProperties(Path workspace) {
        return new SceneAssetStorageProperties(
                workspace,
                properties.maxUploadBytes(),
                properties.maxExpandedBytes(),
                properties.maxSingleFileBytes(),
                properties.maxFileCount(),
                properties.maxCompressionRatio(),
                properties.maxJsonBytes(),
                properties.orphanGracePeriod());
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}
