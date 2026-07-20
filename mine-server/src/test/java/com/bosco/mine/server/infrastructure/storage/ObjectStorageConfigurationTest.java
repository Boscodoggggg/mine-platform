package com.bosco.mine.server.infrastructure.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验对象存储 Spring 配置只暴露供应商中立端口。
 */
class ObjectStorageConfigurationTest {

    /** 标准 S3 配置应创建可关闭客户端和唯一对象存储实现。 */
    @Test
    void 应创建标准S3对象存储() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("http://127.0.0.1:9000"), "us-east-1",
                        "mine-scene-assets", "access", "secret", true));
        ObjectStorageConfiguration configuration = new ObjectStorageConfiguration();

        try (S3Client client = configuration.s3Client(properties)) {
            ObjectStorage storage = configuration.objectStorage(client, properties);
            assertThat(storage).isInstanceOf(S3ObjectStorage.class);
        }
    }
}
