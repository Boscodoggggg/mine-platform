package com.bosco.mine.server.infrastructure.storage;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Objects;

/**
 * 在明确启用时通过标准 S3 API 初始化应用 bucket。
 */
final class S3BucketInitializer implements ApplicationRunner {

    private final S3Client client;
    private final String bucket;

    /**
     * 创建开发环境 bucket 初始化器。
     *
     * @param client 标准 S3 客户端
     * @param bucket 应用 bucket
     */
    S3BucketInitializer(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "S3 客户端不能为空");
        this.bucket = Objects.requireNonNull(bucket, "S3 bucket 不能为空");
    }

    /**
     * 检查 bucket，并且仅在服务明确返回不存在时创建。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        HeadBucketRequest request = HeadBucketRequest.builder()
                .bucket(bucket)
                .build();
        try {
            client.headBucket(request);
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucket)
                    .build());
        }
    }
}
