package com.bosco.mine.server.infrastructure.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 校验开发环境 bucket 初始化仅在缺失时执行。
 */
class S3BucketInitializerTest {

    /** 已存在的 bucket 不应重复创建。 */
    @Test
    void 已存在Bucket不应创建() {
        S3Client client = mock(S3Client.class);

        new S3BucketInitializer(client, "mine-scene-assets").run(null);

        verify(client).headBucket(HeadBucketRequest.builder()
                .bucket("mine-scene-assets").build());
        verify(client, never()).createBucket(
                CreateBucketRequest.builder().bucket("mine-scene-assets").build());
    }

    /** 缺失的 bucket 应通过标准 S3 API 创建。 */
    @Test
    void 缺失Bucket应创建() {
        S3Client client = mock(S3Client.class);
        HeadBucketRequest request = HeadBucketRequest.builder()
                .bucket("mine-scene-assets").build();
        doThrow(S3Exception.builder().statusCode(404).build())
                .when(client).headBucket(request);

        new S3BucketInitializer(client, "mine-scene-assets").run(null);

        verify(client).createBucket(CreateBucketRequest.builder()
                .bucket("mine-scene-assets").build());
    }
}
