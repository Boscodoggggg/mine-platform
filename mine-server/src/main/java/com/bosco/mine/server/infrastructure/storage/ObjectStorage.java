package com.bosco.mine.server.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 供应商中立的对象存储访问端口。
 */
public interface ObjectStorage {

    /**
     * 写入或幂等覆盖一个对象。
     *
     * @param objectKey bucket 内对象键
     * @param source 对象内容流
     * @param contentLength 对象字节数
     * @param contentType 对象媒体类型
     */
    void put(String objectKey, InputStream source, long contentLength, String contentType);

    /**
     * 查询对象元数据。
     *
     * @param objectKey bucket 内对象键
     * @return 对象元数据
     */
    ObjectMetadata head(String objectKey);

    /**
     * 完整读取对象。
     *
     * @param objectKey bucket 内对象键
     * @return 需要调用方关闭的对象内容
     */
    default ObjectContent get(String objectKey) {
        return get(objectKey, null);
    }

    /**
     * 完整或按单范围读取对象。
     *
     * @param objectKey bucket 内对象键
     * @param range 可为空的闭区间范围
     * @return 需要调用方关闭的对象内容
     */
    ObjectContent get(String objectKey, ByteRange range);

    /**
     * 删除一个对象；对象不存在时应保持幂等。
     *
     * @param objectKey bucket 内对象键
     */
    void delete(String objectKey);

    /**
     * 校验对象键不为空且不是绝对路径。
     *
     * @param objectKey 待校验对象键
     * @return 原对象键
     */
    static String requireObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()
                || objectKey.startsWith("/") || objectKey.contains("\\")) {
            throw new IllegalArgumentException("对象键格式无效");
        }
        return objectKey;
    }

    /**
     * 对象单范围闭区间。
     *
     * @param startInclusive 起始字节位置
     * @param endInclusive 结束字节位置
     */
    record ByteRange(long startInclusive, long endInclusive) {
        /** 校验范围非负且起点不大于终点。 */
        public ByteRange {
            if (startInclusive < 0 || endInclusive < startInclusive) {
                throw new IllegalArgumentException("对象读取范围无效");
            }
        }

        /** @return 标准 HTTP Range 请求值 */
        public String headerValue() {
            return "bytes=" + startInclusive + "-" + endInclusive;
        }
    }

    /**
     * 对象元数据。
     *
     * @param contentLength 对象字节数
     * @param contentType 对象媒体类型
     * @param etag 对象实体标签
     */
    record ObjectMetadata(long contentLength, String contentType, String etag) {
        /** 校验元数据基本约束。 */
        public ObjectMetadata {
            if (contentLength < 0) {
                throw new IllegalArgumentException("对象长度不能小于零");
            }
            contentType = Objects.requireNonNullElse(contentType, "application/octet-stream");
            etag = Objects.requireNonNullElse(etag, "");
        }
    }

    /**
     * 对象读取结果。
     *
     * @param inputStream 对象内容流
     * @param metadata 当前响应元数据
     * @param contentRange 范围响应头；完整读取时为空
     */
    record ObjectContent(
            InputStream inputStream,
            ObjectMetadata metadata,
            String contentRange
    ) implements AutoCloseable {
        /** 校验读取结果必须包含内容流和元数据。 */
        public ObjectContent {
            inputStream = Objects.requireNonNull(inputStream, "对象内容流不能为空");
            metadata = Objects.requireNonNull(metadata, "对象元数据不能为空");
        }

        /** 关闭底层对象响应流。 */
        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
