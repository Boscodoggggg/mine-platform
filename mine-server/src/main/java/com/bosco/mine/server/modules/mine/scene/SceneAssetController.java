package com.bosco.mine.server.modules.mine.scene;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneAssetResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 提供矿区三维资源上传、分页清单、归档和支持 Range 的受权文件读取接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/scene-assets")
public class SceneAssetController {

    private static final CacheControl IMMUTABLE_CACHE = CacheControl
            .maxAge(Duration.ofDays(365))
            .cachePrivate()
            .immutable();

    private final SceneAssetService service;

    /**
     * 创建三维资源控制器。
     *
     * @param service 三维资源应用服务
     */
    public SceneAssetController(SceneAssetService service) {
        this.service = service;
    }

    /** 上传并安全校验一个 GLB 或 3D Tiles ZIP 资源。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("mine:scene:asset:upload")
    public ResponseEntity<SceneAssetResponse> upload(
            @PathVariable UUID siteId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        SceneAssetResponse response = service.upload(
                siteId, file.getInputStream(), file.getOriginalFilename(), file.getContentType());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** 分页查询矿区三维资源。 */
    @GetMapping
    @SaCheckPermission("mine:scene:view")
    public PageResponse<SceneAssetResponse> findAssets(
            @PathVariable UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.findAssets(siteId, status, page, size);
    }

    /** 查询资源详情和一页文件清单。 */
    @GetMapping("/{assetId}")
    @SaCheckPermission("mine:scene:view")
    public SceneAssetResponse findAsset(
            @PathVariable UUID siteId,
            @PathVariable UUID assetId,
            @RequestParam(defaultValue = "1") @Min(1) int filePage,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int fileSize
    ) {
        return service.findAsset(siteId, assetId, filePage, fileSize);
    }

    /** 使用乐观版本归档资源。 */
    @PostMapping("/{assetId}/archive")
    @SaCheckPermission("mine:scene:edit")
    public SceneAssetResponse archive(
            @PathVariable UUID siteId,
            @PathVariable UUID assetId,
            @RequestParam @Min(0) long version
    ) {
        return service.archive(siteId, assetId, version);
    }

    /**
     * 条件读取一个已登记资源文件，只支持单一字节范围。
     *
     * @param siteId 矿区标识
     * @param assetId 资源标识
     * @param relativePath 包内完整相对路径
     * @param rangeHeader 可选单一 Range 请求头
     * @param ifNoneMatch 可选条件缓存摘要
     * @return 完整、部分、未修改或范围不可满足响应
     */
    @GetMapping("/{assetId}/files/{*relativePath}")
    @SaCheckPermission("mine:scene:view")
    public ResponseEntity<?> readFile(
            @PathVariable UUID siteId,
            @PathVariable UUID assetId,
            @PathVariable @NotBlank String relativePath,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        String normalizedPath = relativePath.startsWith("/")
                ? relativePath.substring(1) : relativePath;
        SceneAssetService.SceneAssetFileContent file = service.openFile(
                siteId, assetId, normalizedPath);
        String etag = quotedEtag(file.contentDigest());
        HttpHeaders headers = responseHeaders(file, etag);
        if (etagMatches(ifNoneMatch, etag)) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }

        Resource resource = new FileSystemResource(file.path());
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(file.sizeBytes());
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.size() != 1) {
                return rangeNotSatisfiable(headers, file.sizeBytes());
            }
            long start = ranges.getFirst().getRangeStart(file.sizeBytes());
            long end = ranges.getFirst().getRangeEnd(file.sizeBytes());
            long count = end - start + 1;
            if (start >= file.sizeBytes() || count < 1 || end >= file.sizeBytes()) {
                return rangeNotSatisfiable(headers, file.sizeBytes());
            }
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + file.sizeBytes());
            headers.setContentLength(count);
            return new ResponseEntity<>(
                    new RangeFileResource(file.path(), start, count),
                    headers,
                    HttpStatus.PARTIAL_CONTENT);
        } catch (IllegalArgumentException exception) {
            return rangeNotSatisfiable(headers, file.sizeBytes());
        }
    }

    private HttpHeaders responseHeaders(
            SceneAssetService.SceneAssetFileContent file,
            String etag
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl(IMMUTABLE_CACHE);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        return headers;
    }

    private ResponseEntity<Void> rangeNotSatisfiable(HttpHeaders headers, long length) {
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
        return new ResponseEntity<>(null, headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    private String quotedEtag(String digest) {
        return "\"" + digest.replace("\"", "") + "\"";
    }

    private boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.strip();
            if ("*".equals(normalized) || etag.equals(normalized)
                    || (normalized.startsWith("W/") && etag.equals(normalized.substring(2)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只暴露受权普通文件中一个连续字节区间的资源视图。
     */
    private static final class RangeFileResource extends AbstractResource {

        private final Path path;
        private final long position;
        private final long count;

        private RangeFileResource(Path path, long position, long count) {
            this.path = path;
            this.position = position;
            this.count = count;
        }

        /** 返回不包含宿主机绝对路径的固定资源说明。 */
        @Override
        public String getDescription() {
            return "authorized-scene-asset-range";
        }

        /** 打开文件、跳至范围起点并限制最多读取指定字节数。 */
        @Override
        public InputStream getInputStream() throws IOException {
            InputStream input = Files.newInputStream(path);
            try {
                input.skipNBytes(position);
                return new BoundedInputStream(input, count);
            } catch (IOException exception) {
                input.close();
                throw exception;
            }
        }

        /** 返回范围内容长度，避免消息转换器遍历文件。 */
        @Override
        public long contentLength() {
            return count;
        }

        /** 返回不含路径的文件名，仅用于框架资源描述。 */
        @Override
        public String getFilename() {
            return path.getFileName().toString();
        }
    }

    /**
     * 在底层流之上强制限制最大可读字节数。
     */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        private BoundedInputStream(InputStream input, long count) {
            super(input);
            this.remaining = count;
        }

        /** 读取单个字节且不越过授权范围。 */
        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        /** 批量读取且不越过授权范围。 */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int boundedLength = Math.toIntExact(Math.min(length, remaining));
            int read = super.read(bytes, offset, boundedLength);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        /** 跳过字节时同步扣减剩余授权范围。 */
        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }

        /** 返回当前范围内可立即读取的估算字节数。 */
        @Override
        public int available() throws IOException {
            return Math.toIntExact(Math.min(super.available(), remaining));
        }
    }
}
