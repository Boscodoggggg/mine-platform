package com.bosco.mine.server.modules.mine.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 坐标转换版本响应。
 *
 * @param id 转换版本标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param sourceCoordinateSystemId 源坐标系标识
 * @param targetCoordinateSystemId 目标坐标系标识
 * @param versionNumber 业务版本号
 * @param algorithm 转换算法
 * @param parameters 算法参数
 * @param validFrom 生效起始时间
 * @param validTo 生效结束时间
 * @param status 发布状态
 * @param contentHash 发布内容摘要
 * @param publishedAt 发布时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record CoordinateTransformResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID sourceCoordinateSystemId,
        UUID targetCoordinateSystemId,
        int versionNumber,
        String algorithm,
        JsonNode parameters,
        Instant validFrom,
        Instant validTo,
        String status,
        String contentHash,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
