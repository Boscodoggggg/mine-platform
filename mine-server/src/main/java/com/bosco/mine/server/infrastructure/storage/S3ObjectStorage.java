package com.bosco.mine.server.infrastructure.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.Objects;

/**
 * 使用 AWS SDK 标准 S3 API 实现的对象存储适配器。
 */
public final class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final String bucket;

    /**
     * 创建标准 S3 对象存储适配器。
     *
     * @param client S3 同步客户端
     * @param properties 对象存储配置
     */
    public S3ObjectStorage(S3Client client, ObjectStorageProperties properties) {
        this.client = Objects.requireNonNull(client, "S3 客户端不能为空");
        this.bucket = Objects.requireNonNull(properties, "对象存储配置不能为空")
                .s3().bucket();
    }

    @Override
    public void put(
            String objectKey,
            InputStream source,
            long contentLength,
            String contentType
    ) {
        String key = ObjectStorage.requireObjectKey(objectKey);
        Objects.requireNonNull(source, "对象上传流不能为空");
        if (contentLength < 0) {
            throw new IllegalArgumentException("对象上传长度不能小于零");
        }
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentLength(contentLength)
                    .contentType(Objects.requireNonNullElse(
                            contentType, "application/octet-stream"))
                    .build();
            client.putObject(request, RequestBody.fromInputStream(source, contentLength));
        } catch (RuntimeException exception) {
            throw new ObjectStorageException("对象写入失败", exception);
        }
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        String key = ObjectStorage.requireObjectKey(objectKey);
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return new ObjectMetadata(
                    response.contentLength(), response.contentType(), response.eTag());
        } catch (RuntimeException exception) {
            throw new ObjectStorageException("对象元数据查询失败", exception);
        }
    }

    @Override
    public ObjectContent get(String objectKey, ByteRange range) {
        String key = ObjectStorage.requireObjectKey(objectKey);
        try {
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key);
            if (range != null) {
                builder.range(range.headerValue());
            }
            ResponseInputStream<GetObjectResponse> input = client.getObject(builder.build());
            GetObjectResponse response = input.response();
            return new ObjectContent(
                    input,
                    new ObjectMetadata(
                            response.contentLength(), response.contentType(), response.eTag()),
                    response.contentRange());
        } catch (RuntimeException exception) {
            throw new ObjectStorageException("对象读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        String key = ObjectStorage.requireObjectKey(objectKey);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (RuntimeException exception) {
            throw new ObjectStorageException("对象删除失败", exception);
        }
    }
}
