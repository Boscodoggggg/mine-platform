package com.bosco.mine.server.modules.mine.scene.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 三维资源文件系统存储和恶意包防护边界。
 *
 * @param root 存储根目录
 * @param maxUploadBytes 单次上传最大字节数
 * @param maxExpandedBytes 单个资源展开后最大总字节数
 * @param maxSingleFileBytes 包内单文件最大字节数
 * @param maxFileCount 包内最大文件数量
 * @param maxCompressionRatio 最大总展开压缩比
 * @param maxJsonBytes 单个 JSON 文件最大字节数
 * @param orphanGracePeriod 未登记内容对象清理宽限期
 */
@ConfigurationProperties("mine.scene-storage")
public record SceneAssetStorageProperties(
        Path root,
        long maxUploadBytes,
        long maxExpandedBytes,
        long maxSingleFileBytes,
        int maxFileCount,
        double maxCompressionRatio,
        long maxJsonBytes,
        Duration orphanGracePeriod
) {

    /** 校验并规范化存储边界，非法配置在应用启动阶段立即失败。 */
    public SceneAssetStorageProperties {
        root = Objects.requireNonNull(root, "三维资源存储根目录不能为空")
                .toAbsolutePath()
                .normalize();
        if (maxUploadBytes <= 0) {
            throw new IllegalArgumentException("三维资源上传上限必须大于零");
        }
        if (maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("三维资源展开上限必须大于零");
        }
        if (maxSingleFileBytes <= 0 || maxSingleFileBytes > maxExpandedBytes) {
            throw new IllegalArgumentException("三维资源单文件上限必须位于展开上限内");
        }
        if (maxFileCount < 1 || maxFileCount > 100_000) {
            throw new IllegalArgumentException("三维资源文件数上限必须位于一到十万之间");
        }
        if (!Double.isFinite(maxCompressionRatio)
                || maxCompressionRatio < 1D || maxCompressionRatio > 10_000D) {
            throw new IllegalArgumentException("三维资源压缩比上限必须位于一到一万之间");
        }
        if (maxJsonBytes <= 0 || maxJsonBytes > maxSingleFileBytes) {
            throw new IllegalArgumentException("三维资源 JSON 上限必须位于单文件上限内");
        }
        if (orphanGracePeriod == null
                || orphanGracePeriod.isZero() || orphanGracePeriod.isNegative()) {
            throw new IllegalArgumentException("三维资源孤儿对象宽限期必须大于零");
        }
    }
}
