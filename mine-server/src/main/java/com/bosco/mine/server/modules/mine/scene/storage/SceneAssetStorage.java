package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
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
     * @return 不经过符号链接的普通文件路径
     * @throws IOException 对象缺失、被篡改或路径不是普通文件
     */
    Path resolve(String contentDigest, String relativePath) throws IOException;

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
