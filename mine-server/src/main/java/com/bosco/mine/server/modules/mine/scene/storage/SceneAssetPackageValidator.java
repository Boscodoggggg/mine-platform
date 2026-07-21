package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 对 GLB 和 ZIP 形式 3D Tiles 执行有界、无外链的流式安全校验。
 */
@Component
public final class SceneAssetPackageValidator {

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_JSON_CHUNK = 0x4E4F534A;
    private static final int GLB_BINARY_CHUNK = 0x004E4942;
    private static final int ZIP_CENTRAL_SIGNATURE = 0x02014B50;
    private static final int ZIP_END_SIGNATURE = 0x06054B50;
    private static final int UNIX_HOST = 3;
    private static final int UNIX_FILE_TYPE_MASK = 0xF000;
    private static final int UNIX_SYMBOLIC_LINK = 0xA000;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Set<String> ALLOWED_TILE_EXTENSIONS = Set.of(
            "json", "gltf", "glb", "bin", "b3dm", "i3dm", "pnts", "cmpt",
            "subtree", "png", "jpg", "jpeg", "webp", "ktx2");
    private static final Set<String> RESOURCE_REFERENCE_FIELDS = Set.of(
            "uri", "url", "schemaUri");
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("json", "application/json"),
            Map.entry("gltf", "model/gltf+json"),
            Map.entry("glb", "model/gltf-binary"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("ktx2", "image/ktx2"));

    private final SceneAssetStorageProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建使用统一存储边界和受管 JSON 解析器的包校验器。
     *
     * @param properties 存储安全边界
     * @param objectMapper 受管 JSON 解析器
     */
    public SceneAssetPackageValidator(
            SceneAssetStorageProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "三维资源存储配置不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空");
    }

    /**
     * 校验源文件并把可发布普通文件写入全新的输出目录。
     *
     * @param source 已完成上传的隔离源文件
     * @param assetType 三维资源类型
     * @param outputDirectory 全新的同盘发布候选目录
     * @return 规范化资源入口和文件清单
     * @throws IOException 读取、格式或安全校验失败
     */
    public ValidatedPackage validate(
            Path source,
            SceneAssetPolicy.SceneAssetType assetType,
            Path outputDirectory
    ) throws IOException {
        Objects.requireNonNull(source, "三维资源源文件不能为空");
        Objects.requireNonNull(assetType, "三维资源类型不能为空");
        Objects.requireNonNull(outputDirectory, "三维资源输出目录不能为空");
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new ValidationException("三维资源源文件必须是不经过符号链接的普通文件");
        }
        long sourceSize = Files.size(source);
        if (sourceSize < 1 || sourceSize > properties.maxUploadBytes()) {
            throw new ValidationException("三维资源上传大小超出允许范围");
        }
        if (Files.exists(outputDirectory)) {
            throw new ValidationException("三维资源输出目录必须为全新目录");
        }
        try {
            return switch (assetType) {
                case GLB -> validateGlbPackage(source, outputDirectory);
                case TILES_3D -> validateTilesPackage(source, sourceSize, outputDirectory);
            };
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(outputDirectory);
            if (exception instanceof ValidationException validationException) {
                throw validationException;
            }
            if (exception instanceof ZipException) {
                throw new ValidationException("三维瓦片压缩包结构无效", exception);
            }
            if (exception instanceof RuntimeException) {
                throw new ValidationException("三维资源校验失败", exception);
            }
            throw exception;
        }
    }

    private ValidatedPackage validateGlbPackage(Path source, Path outputDirectory)
            throws IOException {
        long size = Files.size(source);
        if (size > properties.maxSingleFileBytes() || size > properties.maxExpandedBytes()) {
            throw new ValidationException("GLB 文件大小超出允许范围");
        }
        JsonNode glbJson = validateGlb(source);
        validateJsonUris(glbJson, "model.glb", Map.of());
        Files.createDirectory(outputDirectory);
        Path target = outputDirectory.resolve("model.glb");
        Files.copy(source, target);
        ValidatedFile file = new ValidatedFile(
                "model.glb", "model/gltf-binary", digest(target), size);
        return new ValidatedPackage("model.glb", size, List.of(file));
    }

    private ValidatedPackage validateTilesPackage(
            Path source,
            long sourceSize,
            Path outputDirectory
    ) throws IOException {
        inspectZipCentralDirectory(source);
        Files.createDirectory(outputDirectory);
        List<ValidatedFile> files = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        long expandedSize = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                Files.newInputStream(source)), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String rawName = entry.getName();
                boolean directory = entry.isDirectory() || rawName.endsWith("/");
                String candidate = directory
                        ? rawName.substring(0, rawName.length() - 1) : rawName;
                String relativePath = safePackagePath(candidate);
                String caseKey = relativePath.toLowerCase(Locale.ROOT);
                if (!seenPaths.add(caseKey)) {
                    throw new ValidationException("三维瓦片包包含重复或大小写冲突路径");
                }
                Path target = safeOutputPath(outputDirectory, relativePath);
                if (directory) {
                    Files.createDirectories(target);
                    zip.closeEntry();
                    continue;
                }
                if (files.size() >= properties.maxFileCount()) {
                    throw new ValidationException("三维瓦片包文件数量超出允许范围");
                }
                String extension = allowedExtension(relativePath);
                Files.createDirectories(target.getParent());
                long fileSize = extractEntry(zip, target, expandedSize, sourceSize);
                expandedSize += fileSize;
                if ("json".equals(extension) && fileSize > properties.maxJsonBytes()) {
                    throw new ValidationException("三维瓦片 JSON 文件大小超出允许范围");
                }
                files.add(new ValidatedFile(
                        relativePath,
                        CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"),
                        digest(target),
                        fileSize));
                zip.closeEntry();
            }
        }
        if (files.isEmpty()) {
            throw new ValidationException("三维瓦片包不能为空");
        }
        files.sort(Comparator.comparing(ValidatedFile::relativePath));
        String entryPath = uniqueTilesetEntry(files);
        validateExtractedPackage(outputDirectory, entryPath, files);
        return new ValidatedPackage(entryPath, expandedSize, files);
    }

    private long extractEntry(
            InputStream input,
            Path target,
            long previousExpandedSize,
            long sourceSize
    ) throws IOException {
        long fileSize = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileSize += read;
                long expanded = previousExpandedSize + fileSize;
                if (fileSize > properties.maxSingleFileBytes()) {
                    throw new ValidationException("三维瓦片包单文件大小超出允许范围");
                }
                if (expanded > properties.maxExpandedBytes()) {
                    throw new ValidationException("三维瓦片包展开总量超出允许范围");
                }
                if (expanded > sourceSize * properties.maxCompressionRatio()) {
                    throw new ValidationException("三维瓦片包压缩比超出允许范围");
                }
                output.write(buffer, 0, read);
            }
        }
        if (fileSize == 0) {
            throw new ValidationException("三维瓦片包不允许空文件");
        }
        return fileSize;
    }

    private void validateExtractedPackage(
            Path outputDirectory,
            String entryPath,
            List<ValidatedFile> files
    ) throws IOException {
        Map<String, String> actualPaths = new HashMap<>();
        for (ValidatedFile file : files) {
            actualPaths.put(file.relativePath().toLowerCase(Locale.ROOT), file.relativePath());
        }
        for (ValidatedFile file : files) {
            if (file.relativePath().toLowerCase(Locale.ROOT).endsWith(".glb")) {
                JsonNode glbJson = validateGlb(
                        safeOutputPath(outputDirectory, file.relativePath()));
                validateJsonUris(glbJson, file.relativePath(), actualPaths);
            }
        }
        Path tileset = safeOutputPath(outputDirectory, entryPath);
        JsonNode root = readJson(tileset);
        if (!root.isObject()
                || root.get("asset") == null || !root.get("asset").isObject()
                || root.get("root") == null || !root.get("root").isObject()) {
            throw new ValidationException("三维瓦片入口缺少 asset 或 root 对象");
        }
        for (ValidatedFile file : files) {
            String lower = file.relativePath().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json") || lower.endsWith(".gltf")) {
                validateJsonUris(
                        readJson(safeOutputPath(outputDirectory, file.relativePath())),
                        file.relativePath(), actualPaths);
            }
        }
    }

    private JsonNode readJson(Path path) throws IOException {
        if (Files.size(path) > properties.maxJsonBytes()) {
            throw new ValidationException("三维资源 JSON 文件大小超出允许范围");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return objectMapper.readTree(input);
        } catch (RuntimeException exception) {
            throw new ValidationException("三维资源 JSON 结构无效", exception);
        }
    }

    private void validateJsonUris(
            JsonNode node,
            String containingFile,
            Map<String, String> actualPaths
    ) throws ValidationException {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (RESOURCE_REFERENCE_FIELDS.contains(property.getKey())) {
                    JsonNode value = property.getValue();
                    if (!value.isString() || value.asString().isBlank()) {
                        throw new ValidationException("三维资源 URI 必须为非空字符串");
                    }
                    validateInternalUri(containingFile, value.asString(), actualPaths);
                } else {
                    validateJsonUris(property.getValue(), containingFile, actualPaths);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                validateJsonUris(child, containingFile, actualPaths);
            }
        }
    }

    private void validateInternalUri(
            String containingFile,
            String uri,
            Map<String, String> actualPaths
    ) throws ValidationException {
        if (!uri.equals(uri.strip())
                || uri.indexOf('%') >= 0 || uri.indexOf('?') >= 0 || uri.indexOf('#') >= 0) {
            throw new ValidationException("三维资源 URI 不允许编码、查询或片段");
        }
        String safeUri = safePackagePath(uri);
        int separator = containingFile.lastIndexOf('/');
        String resolved = separator < 0
                ? safeUri : containingFile.substring(0, separator + 1) + safeUri;
        resolved = safePackagePath(resolved);
        String actual = actualPaths.get(resolved.toLowerCase(Locale.ROOT));
        if (!resolved.equals(actual)) {
            throw new ValidationException("三维资源 URI 指向不存在或大小写不匹配的包内文件");
        }
    }

    private JsonNode validateGlb(Path source) throws IOException {
        long fileSize = Files.size(source);
        if (fileSize < 20 || fileSize > Integer.MAX_VALUE) {
            throw new ValidationException("GLB 文件长度无效");
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer header = readBuffer(channel, 0, 12);
            if (header.getInt() != GLB_MAGIC || header.getInt() != 2
                    || Integer.toUnsignedLong(header.getInt()) != fileSize) {
                throw new ValidationException("GLB 魔数、版本或声明长度无效");
            }
            long position = 12;
            boolean firstChunk = true;
            JsonNode jsonRoot = null;
            while (position < fileSize) {
                if (fileSize - position < 8) {
                    throw new ValidationException("GLB 分块头不完整");
                }
                ByteBuffer chunkHeader = readBuffer(channel, position, 8);
                long chunkLength = Integer.toUnsignedLong(chunkHeader.getInt());
                int chunkType = chunkHeader.getInt();
                if (chunkLength == 0 || chunkLength % 4 != 0
                        || position + 8 + chunkLength > fileSize
                        || (firstChunk && chunkType != GLB_JSON_CHUNK)
                        || (!firstChunk && chunkType != GLB_BINARY_CHUNK)) {
                    throw new ValidationException("GLB 分块结构无效");
                }
                if (firstChunk) {
                    if (chunkLength > properties.maxJsonBytes()) {
                        throw new ValidationException("GLB JSON 分块大小超出允许范围");
                    }
                    ByteBuffer jsonBuffer = readBuffer(
                            channel, position + 8, Math.toIntExact(chunkLength));
                    try {
                        jsonRoot = objectMapper.readTree(jsonBuffer.array());
                    } catch (RuntimeException exception) {
                        throw new ValidationException("GLB JSON 分块结构无效", exception);
                    }
                    if (jsonRoot == null || !jsonRoot.isObject()) {
                        throw new ValidationException("GLB JSON 分块必须为对象");
                    }
                }
                position += 8 + chunkLength;
                firstChunk = false;
            }
            if (firstChunk || jsonRoot == null || position != fileSize) {
                throw new ValidationException("GLB 文件缺少有效分块");
            }
            return jsonRoot;
        }
    }

    private void inspectZipCentralDirectory(Path source) throws IOException {
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            int tailLength = (int) Math.min(fileSize, 65_557L);
            ByteBuffer tail = readBuffer(channel, fileSize - tailLength, tailLength);
            int endOffset = findSignatureBackwards(tail, ZIP_END_SIGNATURE);
            if (endOffset < 0 || tailLength - endOffset < 22) {
                throw new ValidationException("三维瓦片包缺少有效中央目录");
            }
            int disk = Short.toUnsignedInt(tail.getShort(endOffset + 4));
            int centralDisk = Short.toUnsignedInt(tail.getShort(endOffset + 6));
            int diskEntries = Short.toUnsignedInt(tail.getShort(endOffset + 8));
            int totalEntries = Short.toUnsignedInt(tail.getShort(endOffset + 10));
            long centralSize = Integer.toUnsignedLong(tail.getInt(endOffset + 12));
            long centralOffset = Integer.toUnsignedLong(tail.getInt(endOffset + 16));
            if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries
                    || totalEntries == 0xFFFF || centralSize == 0xFFFFFFFFL
                    || centralOffset == 0xFFFFFFFFL
                    || totalEntries > properties.maxFileCount() * 2L
                    || centralOffset + centralSize > fileSize) {
                throw new ValidationException("不支持分卷、ZIP64 或超量中央目录");
            }
            long position = centralOffset;
            for (int index = 0; index < totalEntries; index++) {
                ByteBuffer header = readBuffer(channel, position, 46);
                if (header.getInt(0) != ZIP_CENTRAL_SIGNATURE) {
                    throw new ValidationException("三维瓦片包中央目录结构无效");
                }
                int versionMadeBy = Short.toUnsignedInt(header.getShort(4));
                int flags = Short.toUnsignedInt(header.getShort(8));
                int nameLength = Short.toUnsignedInt(header.getShort(28));
                int extraLength = Short.toUnsignedInt(header.getShort(30));
                int commentLength = Short.toUnsignedInt(header.getShort(32));
                int externalAttributes = header.getInt(38);
                int host = (versionMadeBy >>> 8) & 0xff;
                int unixMode = (externalAttributes >>> 16) & 0xffff;
                if ((flags & 1) != 0) {
                    throw new ValidationException("三维瓦片包不允许加密条目");
                }
                if (host == UNIX_HOST
                        && (unixMode & UNIX_FILE_TYPE_MASK) == UNIX_SYMBOLIC_LINK) {
                    throw new ValidationException("三维瓦片包不允许符号链接条目");
                }
                position += 46L + nameLength + extraLength + commentLength;
                if (position > centralOffset + centralSize) {
                    throw new ValidationException("三维瓦片包中央目录长度无效");
                }
            }
            if (position != centralOffset + centralSize) {
                throw new ValidationException("三维瓦片包中央目录条目数不一致");
            }
        }
    }

    private String uniqueTilesetEntry(List<ValidatedFile> files) throws ValidationException {
        List<String> entries = files.stream()
                .map(ValidatedFile::relativePath)
                .filter(path -> {
                    int separator = path.lastIndexOf('/');
                    String name = separator < 0 ? path : path.substring(separator + 1);
                    return "tileset.json".equalsIgnoreCase(name);
                })
                .toList();
        if (entries.size() != 1) {
            throw new ValidationException("三维瓦片包必须唯一包含 tileset.json 入口");
        }
        return entries.getFirst();
    }

    private String safePackagePath(String candidate) throws ValidationException {
        try {
            return SceneAssetPolicy.safeRelativePath(candidate);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("三维资源包内路径不安全", exception);
        }
    }

    private Path safeOutputPath(Path root, String relativePath) throws ValidationException {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ValidationException("三维资源包内路径越过输出目录");
        }
        return resolved;
    }

    private String allowedExtension(String relativePath) throws ValidationException {
        int separator = relativePath.lastIndexOf('.');
        String extension = separator < 0
                ? "" : relativePath.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_TILE_EXTENSIONS.contains(extension)) {
            throw new ValidationException("三维瓦片包包含非白名单文件类型");
        }
        return extension;
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

    private ByteBuffer readBuffer(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new ValidationException("三维资源文件结构提前结束");
            }
        }
        return buffer.flip();
    }

    private int findSignatureBackwards(ByteBuffer buffer, int signature) {
        for (int index = buffer.limit() - 4; index >= 0; index--) {
            if (buffer.getInt(index) == signature) {
                return index;
            }
        }
        return -1;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        } catch (FileAlreadyExistsException ignored) {
            // 并发清理已完成时视为成功。
        }
    }

    /**
     * 已验证并写入发布候选目录的资源包。
     *
     * @param entryPath 资源入口相对路径
     * @param expandedSizeBytes 发布普通文件总字节数
     * @param files 规范化不可变文件清单
     */
    public record ValidatedPackage(
            String entryPath,
            long expandedSizeBytes,
            List<ValidatedFile> files
    ) {
        /** 防止调用方修改验证结果。 */
        public ValidatedPackage {
            files = List.copyOf(files);
        }
    }

    /**
     * 已验证资源包中的单个普通文件。
     *
     * @param relativePath 安全相对路径
     * @param contentType 白名单媒体类型
     * @param contentDigest 文件 SHA-256 摘要
     * @param sizeBytes 文件字节数
     */
    public record ValidatedFile(
            String relativePath,
            String contentType,
            String contentDigest,
            long sizeBytes
    ) {
    }

    /**
     * 表示三维资源不满足固定格式或安全边界。
     */
    public static final class ValidationException extends IOException {

        /**
         * 创建不携带用户内容的固定校验异常。
         *
         * @param message 固定失败原因
         */
        public ValidationException(String message) {
            super(message);
        }

        /**
         * 创建保留底层诊断的固定校验异常。
         *
         * @param message 固定失败原因
         * @param cause 底层异常
         */
        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
