package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用同盘隔离目录和原子移动实现的文件系统内容寻址存储。
 */
@Component
@EnableConfigurationProperties(SceneAssetStorageProperties.class)
public final class FileSystemSceneAssetStorage implements SceneAssetStorage {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int BUFFER_SIZE = 16 * 1024;

    private final SceneAssetStorageProperties properties;
    private final SceneAssetPackageValidator validator;
    private final Path root;
    private final Path stagingRoot;
    private final Path objectsRoot;

    /**
     * 创建并校验三维资源文件系统目录。
     *
     * @param properties 存储安全边界
     * @param validator 三维资源包校验器
     */
    public FileSystemSceneAssetStorage(
            SceneAssetStorageProperties properties,
            SceneAssetPackageValidator validator
    ) {
        this.properties = Objects.requireNonNull(properties, "三维资源存储配置不能为空");
        this.validator = Objects.requireNonNull(validator, "三维资源校验器不能为空");
        this.root = properties.root();
        this.stagingRoot = root.resolve(".staging");
        this.objectsRoot = root.resolve("objects");
        try {
            createOwnedDirectory(root);
            createOwnedDirectory(stagingRoot);
            createOwnedDirectory(objectsRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("三维资源存储目录不可用", exception);
        }
    }

    @Override
    public StoredAsset store(InputStream source, String fileName, String contentType)
            throws IOException {
        Objects.requireNonNull(source, "三维资源上传流不能为空");
        SceneAssetPolicy.SceneAssetType assetType = SceneAssetPolicy.typeFor(
                fileName, contentType);
        Path workspace = stagingRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectory(workspace);
        try {
            Path upload = workspace.resolve("upload");
            UploadResult uploadResult = writeUpload(source, upload);
            Path payload = workspace.resolve("payload");
            SceneAssetPackageValidator.ValidatedPackage validated = validator.validate(
                    upload, assetType, payload);
            Path objectDirectory = objectsRoot.resolve(uploadResult.hexDigest());
            publishObject(payload, objectDirectory, validated.files());
            return storedAsset(assetType, uploadResult, validated);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Override
    public Path resolve(String contentDigest, String relativePath) throws IOException {
        String digest = requireDigest(contentDigest);
        String safePath = SceneAssetPolicy.safeRelativePath(relativePath);
        Path objectDirectory = objectsRoot.resolve(digest.substring("sha256:".length()));
        requireDirectoryWithoutLinks(objectDirectory);
        Path target = objectDirectory.resolve(safePath).normalize();
        if (!target.startsWith(objectDirectory)) {
            throw new IllegalArgumentException("三维资源文件路径越过对象目录");
        }
        requirePathWithoutLinks(objectDirectory, target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("三维资源文件不存在或不是普通文件");
        }
        return target;
    }

    private UploadResult writeUpload(InputStream source, Path target) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(source);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > properties.maxUploadBytes()) {
                    throw new SceneAssetPackageValidator.ValidationException(
                            "三维资源上传大小超出允许范围");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (size == 0) {
            throw new SceneAssetPackageValidator.ValidationException("三维资源上传内容不能为空");
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        return new UploadResult("sha256:" + hex, hex, size);
    }

    private void publishObject(
            Path payload,
            Path objectDirectory,
            List<SceneAssetPackageValidator.ValidatedFile> files
    ) throws IOException {
        if (Files.exists(objectDirectory, LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingObject(objectDirectory, files);
            return;
        }
        try {
            Files.move(payload, objectDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException exception) {
            verifyExistingObject(objectDirectory, files);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("三维资源存储不支持同盘原子发布", exception);
        }
    }

    private void verifyExistingObject(
            Path objectDirectory,
            List<SceneAssetPackageValidator.ValidatedFile> expectedFiles
    ) throws IOException {
        requireDirectoryWithoutLinks(objectDirectory);
        long regularFiles;
        try (var paths = Files.walk(objectDirectory)) {
            regularFiles = paths.filter(path -> !path.equals(objectDirectory))
                    .peek(path -> {
                        if (Files.isSymbolicLink(path)) {
                            throw new UnsafeObjectException();
                        }
                    })
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .count();
        } catch (UnsafeObjectException exception) {
            throw new IOException("内容寻址对象包含符号链接");
        }
        if (regularFiles != expectedFiles.size()) {
            throw new IOException("内容寻址对象文件数量与校验清单不一致");
        }
        for (SceneAssetPackageValidator.ValidatedFile expected : expectedFiles) {
            Path existing = objectDirectory.resolve(expected.relativePath()).normalize();
            requirePathWithoutLinks(objectDirectory, existing);
            if (!Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(existing) != expected.sizeBytes()
                    || !digest(existing).equals(expected.contentDigest())) {
                throw new IOException("内容寻址对象与校验清单不一致");
            }
        }
    }

    private StoredAsset storedAsset(
            SceneAssetPolicy.SceneAssetType assetType,
            UploadResult upload,
            SceneAssetPackageValidator.ValidatedPackage validated
    ) {
        List<StoredFile> files = validated.files().stream()
                .map(file -> new StoredFile(
                        file.relativePath(), file.contentType(),
                        file.contentDigest(), file.sizeBytes()))
                .toList();
        return new StoredAsset(
                assetType,
                upload.contentDigest(),
                upload.sizeBytes(),
                validated.expandedSizeBytes(),
                validated.entryPath(),
                files);
    }

    private String requireDigest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException("三维资源摘要格式无效");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void createOwnedDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectoryWithoutLinks(directory);
            return;
        }
        Files.createDirectories(directory);
        requireDirectoryWithoutLinks(directory);
    }

    private void requireDirectoryWithoutLinks(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("三维资源存储目录不是安全普通目录");
        }
    }

    private void requirePathWithoutLinks(Path base, Path target) throws IOException {
        Path current = base;
        Path relative = base.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("三维资源对象路径包含符号链接");
            }
        }
    }

    private String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", exception);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private record UploadResult(
            String contentDigest,
            String hexDigest,
            long sizeBytes
    ) {
    }

    private static final class UnsafeObjectException extends RuntimeException {
    }
}
