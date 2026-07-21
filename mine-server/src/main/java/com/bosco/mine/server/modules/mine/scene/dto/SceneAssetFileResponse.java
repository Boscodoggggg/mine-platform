package com.bosco.mine.server.modules.mine.scene.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 三维资源中经过校验并允许受权读取的文件清单项。
 *
 * @param id 文件清单标识
 * @param relativePath 包内安全相对路径
 * @param contentType 白名单响应媒体类型
 * @param contentDigest 文件内容 SHA-256 摘要
 * @param sizeBytes 文件字节数
 * @param createdAt 清单登记时间
 */
public record SceneAssetFileResponse(
        UUID id,
        String relativePath,
        String contentType,
        String contentDigest,
        long sizeBytes,
        Instant createdAt
) {
}
