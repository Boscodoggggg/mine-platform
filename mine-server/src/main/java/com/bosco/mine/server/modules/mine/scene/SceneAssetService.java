package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import com.bosco.mine.server.modules.mine.scene.dto.SceneAssetFileResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneAssetResponse;
import com.bosco.mine.server.modules.mine.scene.storage.SceneAssetPackageValidator;
import com.bosco.mine.server.modules.mine.scene.storage.SceneAssetStorage;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 管理矿区三维资源上传登记、分页清单、乐观归档和受权文件解析。
 */
@Service
public class SceneAssetService {

    private static final ErrorCode INVALID_REQUEST =
            new ErrorCode("MINE-SCENE-ASSET-422-001", "三维资源请求不符合安全边界");
    private static final ErrorCode ASSET_NOT_FOUND =
            new ErrorCode("MINE-SCENE-ASSET-404-001", "三维资源不存在");
    private static final ErrorCode FILE_NOT_FOUND =
            new ErrorCode("MINE-SCENE-ASSET-404-002", "三维资源文件不存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("MINE-SCENE-ASSET-409-001", "三维资源状态已变化，请刷新后重试");
    private static final ErrorCode STORAGE_UNAVAILABLE =
            new ErrorCode("MINE-SCENE-ASSET-503-001", "三维资源存储暂时不可用");
    private static final Set<String> STATUSES = Set.of("QUARANTINED", "AVAILABLE", "ARCHIVED");

    private final SceneMapper mapper;
    private final SceneAssetStorage storage;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final SceneActionIdempotencyService idempotencyService;
    private final SceneMetrics metrics;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建三维资源应用服务。
     *
     * @param mapper 场景数据访问接口
     * @param storage 内容寻址存储端口
     * @param authorizer 矿区作用域授权器
     * @param auditService 操作审计服务
     * @param idempotencyService 场景动作幂等服务
     * @param metrics 三维场景运行指标
     */
    public SceneAssetService(
            SceneMapper mapper,
            SceneAssetStorage storage,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            SceneActionIdempotencyService idempotencyService,
            SceneMetrics metrics
    ) {
        this.mapper = Objects.requireNonNull(mapper, "场景数据访问接口不能为空");
        this.storage = Objects.requireNonNull(storage, "三维资源存储不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
        this.auditService = Objects.requireNonNull(auditService, "操作审计服务不能为空");
        this.idempotencyService = Objects.requireNonNull(
                idempotencyService, "场景动作幂等服务不能为空");
        this.metrics = Objects.requireNonNull(metrics, "三维场景指标不能为空");
    }

    /**
     * 流式校验上传内容并按矿区和内容摘要幂等登记。
     *
     * @param siteId 矿区标识
     * @param source 上传内容流
     * @param originalFilename 原始文件名
     * @param contentType 上传媒体类型
     * @return 可用资源及第一页文件清单
     */
    @Transactional
    public SceneAssetResponse upload(
            UUID siteId,
            InputStream source,
            String originalFilename,
            String contentType
    ) {
        MineSite site = authorizer.requireSite(siteId);
        String safeFilename;
        try {
            safeFilename = safeFilename(originalFilename);
        } catch (MineException exception) {
            metrics.failure(
                    SceneMetrics.Stage.VALIDATION,
                    SceneMetrics.Result.REJECTED,
                    SceneMetrics.Reason.INVALID_PACKAGE,
                    SceneMetrics.AssetType.NONE);
            throw exception;
        }
        SceneAssetStorage.StoredAsset stored;
        try {
            stored = storage.store(source, safeFilename, contentType);
        } catch (SceneAssetPackageValidator.ValidationException | IllegalArgumentException exception) {
            metrics.failure(
                    SceneMetrics.Stage.VALIDATION,
                    SceneMetrics.Result.REJECTED,
                    SceneMetrics.Reason.INVALID_PACKAGE,
                    metricAssetType(safeFilename, contentType));
            throw new MineException(INVALID_REQUEST, Map.of());
        } catch (IOException exception) {
            metrics.failure(
                    SceneMetrics.Stage.UPLOAD,
                    SceneMetrics.Result.FAILURE,
                    SceneMetrics.Reason.STORAGE_UNAVAILABLE,
                    metricAssetType(safeFilename, contentType));
            throw new MineException(STORAGE_UNAVAILABLE, Map.of());
        }
        var existing = mapper.findAssetByDigest(siteId, stored.contentDigest());
        if (existing.isPresent()) {
            recordUploadSuccess(stored.assetType());
            return responseWithFiles(existing.get(), 1, 100);
        }

        UUID assetId = idGenerator.next();
        UUID userId = authorizer.currentUserId();
        String canonicalContentType = stored.assetType().name().equals("GLB")
                ? "model/gltf-binary" : "application/zip";
        int inserted = mapper.insertAsset(
                assetId, site.organizationId(), siteId, stored.assetType().name(),
                safeFilename, canonicalContentType, stored.contentDigest(),
                stored.sourceSizeBytes(), stored.expandedSizeBytes(), stored.fileCount(),
                stored.entryPath(), userId);
        if (inserted == 0) {
            recordUploadSuccess(stored.assetType());
            return responseWithFiles(mapper.findAssetByDigest(siteId, stored.contentDigest())
                    .orElseThrow(() -> new MineException(STORAGE_UNAVAILABLE, Map.of())), 1, 100);
        }
        for (SceneAssetStorage.StoredFile file : stored.files()) {
            mapper.insertAssetFile(
                    idGenerator.next(), site.organizationId(), siteId, assetId,
                    file.relativePath(), file.contentType(), file.contentDigest(), file.sizeBytes());
        }
        if (mapper.makeAssetAvailable(siteId, assetId) != 1) {
            throw new MineException(STORAGE_UNAVAILABLE, Map.of());
        }
        SceneMapper.SceneAssetRecord created = requiredAsset(siteId, assetId);
        auditService.recordSuccess(
                "MINE_SCENE", "UPLOAD_SCENE_ASSET", "mine:scene:asset:upload",
                "SCENE_ASSET", assetId, Map.of(),
                Map.of("siteId", siteId, "assetType", created.assetType(),
                        "contentDigest", created.contentDigest(), "fileCount", created.fileCount()));
        recordUploadSuccess(stored.assetType());
        return responseWithFiles(created, 1, 100);
    }

    /**
     * 分页查询矿区三维资源，不展开文件清单。
     *
     * @param siteId 矿区标识
     * @param status 可选状态
     * @param page 页码
     * @param size 每页数量，最大一百
     * @return 资源分页
     */
    @Transactional(readOnly = true)
    public PageResponse<SceneAssetResponse> findAssets(
            UUID siteId,
            String status,
            int page,
            int size
    ) {
        authorizer.requireSite(siteId);
        validatePage(page, size, 100);
        String normalizedStatus = normalizeStatus(status);
        long offset = offset(page, size);
        return new PageResponse<>(
                mapper.findAssets(siteId, normalizedStatus, offset, size).stream()
                        .map(record -> response(record, null))
                        .toList(),
                page, size, mapper.countAssets(siteId, normalizedStatus));
    }

    /**
     * 查询资源详情和一页受控文件清单。
     *
     * @param siteId 矿区标识
     * @param assetId 资源标识
     * @param filePage 文件页码
     * @param fileSize 文件页大小，最大五百
     * @return 资源详情
     */
    @Transactional(readOnly = true)
    public SceneAssetResponse findAsset(
            UUID siteId,
            UUID assetId,
            int filePage,
            int fileSize
    ) {
        authorizer.requireSite(siteId);
        validatePage(filePage, fileSize, 500);
        return responseWithFiles(requiredAsset(siteId, assetId), filePage, fileSize);
    }

    /**
     * 使用持久幂等键和乐观版本归档可用资源；归档只阻止新引用，不破坏历史发布版本读取。
     *
     * @param siteId 矿区标识
     * @param assetId 资源标识
     * @param version 当前乐观版本
     * @param idempotencyKey 调用方稳定幂等键
     * @return 归档后的资源详情
     */
    public SceneAssetResponse archive(
            UUID siteId,
            UUID assetId,
            long version,
            String idempotencyKey
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "ARCHIVE_SCENE_ASSET", "SCENE_ASSET", assetId,
                Map.of("assetId", assetId, "version", version),
                SceneAssetResponse.class,
                () -> archiveOnce(siteId, assetId, version));
    }

    private SceneAssetResponse archiveOnce(UUID siteId, UUID assetId, long version) {
        authorizer.requireSite(siteId);
        SceneMapper.SceneAssetRecord before = requiredAsset(siteId, assetId);
        if (version < 0 || mapper.archiveAsset(siteId, assetId, version) != 1) {
            throw new MineException(VERSION_CONFLICT, Map.of("assetId", assetId));
        }
        SceneMapper.SceneAssetRecord archived = requiredAsset(siteId, assetId);
        auditService.recordSuccess(
                "MINE_SCENE", "ARCHIVE_SCENE_ASSET", "mine:scene:asset:upload",
                "SCENE_ASSET", assetId,
                Map.of("status", before.status(), "version", before.version()),
                Map.of("status", archived.status(), "version", archived.version()));
        return responseWithFiles(archived, 1, 100);
    }

    /**
     * 先验证矿区作用域和数据库清单，再解析内容寻址文件。
     *
     * @param siteId 矿区标识
     * @param assetId 资源标识
     * @param relativePath 完整包内相对路径
     * @return 仅含受信内部路径和白名单元数据的文件内容描述
     */
    @Transactional(readOnly = true)
    public SceneAssetFileContent openFile(
            UUID siteId,
            UUID assetId,
            String relativePath
    ) {
        authorizer.requireSite(siteId);
        SceneMapper.SceneAssetRecord asset = requiredAsset(siteId, assetId);
        SceneMapper.SceneAssetFileRecord file = mapper.findAssetFile(siteId, assetId, relativePath)
                .orElseThrow(() -> new MineException(FILE_NOT_FOUND, Map.of()));
        SceneMetrics.AssetType assetType = SceneMetrics.AssetType.from(
                SceneAssetPolicy.SceneAssetType.valueOf(asset.assetType()));
        return new SceneAssetFileContent(
                file.contentType(), file.contentDigest(), file.sizeBytes(),
                range -> {
                    try {
                        SceneAssetStorage.StoredContent content = storage.open(
                                asset.contentDigest(), file.relativePath(), range);
                        return new SceneAssetStorage.StoredContent(
                                new SceneReadInputStream(
                                        content.inputStream(),
                                        content.contentLength(),
                                        metrics,
                                        assetType),
                                content.contentLength(),
                                content.contentRange());
                    } catch (IOException | IllegalArgumentException exception) {
                        metrics.failure(
                                SceneMetrics.Stage.READ,
                                SceneMetrics.Result.FAILURE,
                                SceneMetrics.Reason.STORAGE_UNAVAILABLE,
                                assetType);
                        throw exception;
                    }
                });
    }

    private void recordUploadSuccess(SceneAssetPolicy.SceneAssetType assetType) {
        SceneMetrics.AssetType metricType = SceneMetrics.AssetType.from(assetType);
        metrics.operation(
                SceneMetrics.Stage.VALIDATION,
                SceneMetrics.Result.SUCCESS,
                SceneMetrics.Reason.NONE,
                metricType);
        metrics.operation(
                SceneMetrics.Stage.UPLOAD,
                SceneMetrics.Result.SUCCESS,
                SceneMetrics.Reason.NONE,
                metricType);
    }

    private SceneMetrics.AssetType metricAssetType(String filename, String contentType) {
        try {
            return SceneMetrics.AssetType.from(SceneAssetPolicy.typeFor(filename, contentType));
        } catch (IllegalArgumentException exception) {
            return SceneMetrics.AssetType.NONE;
        }
    }

    /** 在完整流式响应结束后记录读取结果，覆盖对象存储中途断流。 */
    static final class SceneReadInputStream extends FilterInputStream {

        private final long expectedBytes;
        private final SceneMetrics metrics;
        private final SceneMetrics.AssetType assetType;
        private long bytesRead;
        private boolean recorded;

        /**
         * 创建一个不缓存内容的场景读取计量流。
         *
         * @param input 底层对象内容流
         * @param expectedBytes 期望完整响应字节数
         * @param metrics 三维场景指标
         * @param assetType 固定资源类型
         */
        SceneReadInputStream(
                InputStream input,
                long expectedBytes,
                SceneMetrics metrics,
                SceneMetrics.AssetType assetType
        ) {
            super(Objects.requireNonNull(input, "场景读取流不能为空"));
            if (expectedBytes < 0) {
                throw new IllegalArgumentException("场景读取期望字节数不能小于零");
            }
            this.expectedBytes = expectedBytes;
            this.metrics = Objects.requireNonNull(metrics, "三维场景指标不能为空");
            this.assetType = Objects.requireNonNull(assetType, "场景资源类型不能为空");
        }

        /** 读取单字节并在结束或异常时提交固定结果。 */
        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) {
                    finish(bytesRead == expectedBytes);
                } else {
                    bytesRead++;
                }
                return value;
            } catch (IOException exception) {
                finish(false);
                throw exception;
            }
        }

        /** 读取字节块并在结束或异常时提交固定结果。 */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int count = super.read(buffer, offset, length);
                if (count < 0) {
                    finish(bytesRead == expectedBytes);
                } else {
                    bytesRead += count;
                }
                return count;
            } catch (IOException exception) {
                finish(false);
                throw exception;
            }
        }

        /** 关闭底层响应，并将未完整消费的响应记为读取失败。 */
        @Override
        public void close() throws IOException {
            try {
                super.close();
                finish(bytesRead == expectedBytes);
            } catch (IOException exception) {
                finish(false);
                throw exception;
            }
        }

        private void finish(boolean success) {
            if (recorded) {
                return;
            }
            recorded = true;
            if (success) {
                metrics.operation(
                        SceneMetrics.Stage.READ,
                        SceneMetrics.Result.SUCCESS,
                        SceneMetrics.Reason.NONE,
                        assetType);
            } else {
                metrics.failure(
                        SceneMetrics.Stage.READ,
                        SceneMetrics.Result.FAILURE,
                        SceneMetrics.Reason.STORAGE_UNAVAILABLE,
                        assetType);
            }
        }
    }

    private SceneMapper.SceneAssetRecord requiredAsset(UUID siteId, UUID assetId) {
        return mapper.findAssetById(siteId, assetId)
                .orElseThrow(() -> new MineException(ASSET_NOT_FOUND, Map.of()));
    }

    private SceneAssetResponse responseWithFiles(
            SceneMapper.SceneAssetRecord record,
            int page,
            int size
    ) {
        long offset = offset(page, size);
        PageResponse<SceneAssetFileResponse> files = new PageResponse<>(
                mapper.findAssetFiles(record.siteId(), record.id(), offset, size).stream()
                        .map(this::fileResponse)
                        .toList(),
                page, size, mapper.countAssetFiles(record.siteId(), record.id()));
        return response(record, files);
    }

    private SceneAssetResponse response(
            SceneMapper.SceneAssetRecord record,
            PageResponse<SceneAssetFileResponse> files
    ) {
        return new SceneAssetResponse(
                record.id(), record.organizationId(), record.siteId(), record.assetType(),
                record.originalFilename(), record.contentType(), record.contentDigest(),
                record.sourceSizeBytes(), record.expandedSizeBytes(), record.fileCount(),
                record.entryPath(), record.status(), record.createdAt(), record.createdBy(),
                record.archivedAt(), record.version(), files);
    }

    private SceneAssetFileResponse fileResponse(SceneMapper.SceneAssetFileRecord record) {
        return new SceneAssetFileResponse(
                record.id(), record.relativePath(), record.contentType(),
                record.contentDigest(), record.sizeBytes(), record.createdAt());
    }

    private String safeFilename(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new MineException(INVALID_REQUEST, Map.of());
        }
        return value.strip();
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new MineException(INVALID_REQUEST, Map.of());
        }
        return normalized;
    }

    private void validatePage(int page, int size, int maximumSize) {
        if (page < 1 || size < 1 || size > maximumSize) {
            throw new MineException(INVALID_REQUEST, Map.of());
        }
    }

    private long offset(int page, int size) {
        try {
            return Math.multiplyExact((long) page - 1L, size);
        } catch (ArithmeticException exception) {
            throw new MineException(INVALID_REQUEST, Map.of());
        }
    }

    /**
     * 经过授权和清单校验的内部文件读取描述。
     *
     * @param contentType 白名单媒体类型
     * @param contentDigest 文件内容摘要
     * @param sizeBytes 文件字节数
     * @param reader 通过授权元数据延迟打开内容的读取器
     */
    public record SceneAssetFileContent(
            String contentType,
            String contentDigest,
            long sizeBytes,
            ContentReader reader
    ) {
        /** 校验内部文件描述的必要字段。 */
        public SceneAssetFileContent {
            Objects.requireNonNull(contentType, "三维资源文件媒体类型不能为空");
            Objects.requireNonNull(contentDigest, "三维资源文件摘要不能为空");
            Objects.requireNonNull(reader, "三维资源内容读取器不能为空");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("三维资源文件大小必须大于零");
            }
        }

        /**
         * 延迟打开完整或范围内容，并统一转换存储故障。
         *
         * @param range 可为空的单一范围
         * @return 需要响应写出方关闭的内容流
         */
        public SceneAssetStorage.StoredContent open(SceneAssetStorage.ByteRange range) {
            try {
                return reader.open(range);
            } catch (IOException | IllegalArgumentException exception) {
                throw new MineException(STORAGE_UNAVAILABLE, Map.of());
            }
        }
    }

    /**
     * 已授权场景文件的延迟内容读取器。
     */
    @FunctionalInterface
    public interface ContentReader {

        /**
         * 打开完整或范围内容。
         *
         * @param range 可为空的单一范围
         * @return 需要调用方关闭的内容流
         * @throws IOException 对象存储读取失败
         */
        SceneAssetStorage.StoredContent open(SceneAssetStorage.ByteRange range)
                throws IOException;
    }
}
