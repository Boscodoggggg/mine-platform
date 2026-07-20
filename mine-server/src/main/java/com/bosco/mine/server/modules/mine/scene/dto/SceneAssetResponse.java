package com.bosco.mine.server.modules.mine.scene.dto;

import com.bosco.mine.server.bootstrap.web.PageResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * 矿区三维资源元数据和可选分页文件清单。
 *
 * @param id 资源标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param assetType 固定资源类型
 * @param originalFilename 上传原始文件名
 * @param contentType 规范化上传媒体类型
 * @param contentDigest 上传内容 SHA-256 摘要
 * @param sourceSizeBytes 上传原始字节数
 * @param expandedSizeBytes 校验后展开字节数
 * @param fileCount 文件清单总数
 * @param entryPath 资源入口相对路径
 * @param status 生命周期状态
 * @param createdAt 登记时间
 * @param createdBy 上传用户标识
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 * @param files 详情查询时返回的分页文件清单，列表查询时为空
 */
public record SceneAssetResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String assetType,
        String originalFilename,
        String contentType,
        String contentDigest,
        long sourceSizeBytes,
        long expandedSizeBytes,
        int fileCount,
        String entryPath,
        String status,
        Instant createdAt,
        UUID createdBy,
        Instant archivedAt,
        long version,
        PageResponse<SceneAssetFileResponse> files
) {
}
