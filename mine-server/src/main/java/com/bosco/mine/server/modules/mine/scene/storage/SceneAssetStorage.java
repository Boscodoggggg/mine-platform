package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * 三维资源内容寻址存储端口。
 */
public interface SceneAssetStorage {

    /**
     * 流式校验并原子保存一个三维资源。
     *
     * @param source 上传内容流
     * @param fileName 原始文件名
     * @param contentType 上传媒体类型
     * @return 可用于数据库登记的不可变资源清单
     * @throws IOException 上传、校验或原子发布失败
     */
    StoredAsset store(InputStream source, String fileName, String contentType) throws IOException;

    /**
     * 从受信摘要和已登记相对路径解析可读取的普通文件。
     *
     * @param contentDigest 上传内容摘要
     * @param relativePath 数据库文件清单中的安全相对路径
     * @param range 可为空的单一字节闭区间
     * @return 需要调用方关闭的内容流
     * @throws IOException 对象缺失、被篡改或路径不是普通文件
     */
    StoredContent open(String contentDigest, String relativePath, ByteRange range)
            throws IOException;

    /**
     * 三维资源单一字节闭区间。
     *
     * @param startInclusive 起始字节位置
     * @param endInclusive 结束字节位置
     */
    record ByteRange(long startInclusive, long endInclusive) {
        /** 校验范围非负且起点不大于终点。 */
        public ByteRange {
            if (startInclusive < 0 || endInclusive < startInclusive) {
                throw new IllegalArgumentException("三维资源读取范围无效");
            }
        }
    }

    /**
     * 三维资源读取结果。
     *
     * @param inputStream 内容流
     * @param contentLength 当前响应字节数
     * @param contentRange 范围响应描述；完整读取时为空
     */
    record StoredContent(
            InputStream inputStream,
            long contentLength,
            String contentRange
    ) implements AutoCloseable {
        /** 校验读取内容和长度。 */
        public StoredContent {
            Objects.requireNonNull(inputStream, "三维资源内容流不能为空");
            if (contentLength < 0) {
                throw new IllegalArgumentException("三维资源内容长度不能小于零");
            }
        }

        /** 关闭底层内容流。 */
        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }

    /**
     * 已校验并发布的三维资源清单。
     *
     * @param assetType 三维资源类型
     * @param contentDigest 上传内容 SHA-256 摘要
     * @param sourceSizeBytes 上传原始字节数
     * @param expandedSizeBytes 发布文件总字节数
     * @param entryPath 资源入口相对路径
     * @param files 不可变文件清单
     */
    record StoredAsset(
            SceneAssetPolicy.SceneAssetType assetType,
            String contentDigest,
            long sourceSizeBytes,
            long expandedSizeBytes,
            String entryPath,
            List<StoredFile> files
    ) {
        /** 防止调用方修改存储返回的文件清单。 */
        public StoredAsset {
            Objects.requireNonNull(assetType, "三维资源类型不能为空");
            Objects.requireNonNull(contentDigest, "三维资源摘要不能为空");
            Objects.requireNonNull(entryPath, "三维资源入口不能为空");
            files = List.copyOf(files);
        }

        /** @return 资源文件数量 */
        public int fileCount() {
            return files.size();
        }
    }

    /**
     * 已校验三维资源中的单个普通文件。
     *
     * @param relativePath 安全相对路径
     * @param contentType 白名单响应媒体类型
     * @param contentDigest 文件内容 SHA-256 摘要
     * @param sizeBytes 文件字节数
     */
    record StoredFile(
            String relativePath,
            String contentType,
            String contentDigest,
            long sizeBytes
    ) {
    }
}
