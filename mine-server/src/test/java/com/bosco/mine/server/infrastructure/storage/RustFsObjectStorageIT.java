package com.bosco.mine.server.infrastructure.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用固定版本 RustFS 校验通用对象存储端口的标准 S3 契约。
 */
@Testcontainers
class RustFsObjectStorageIT {

    private static final String ACCESS_KEY = "mine-rustfs-it";
    private static final String SECRET_KEY = "mine-rustfs-it-secret";
    private static final String BUCKET = "mine-test";

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>(
            "rustfs/rustfs:1.0.0-beta.10")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
            .withEnv("RUSTFS_ADDRESS", "0.0.0.0:9000")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000));

    private static S3Client client;
    private static ObjectStorage storage;

    /** 创建测试 bucket，并以应用实际使用的配置构造对象存储适配器。 */
    @BeforeAll
    static void 创建对象存储() {
        URI endpoint = URI.create("http://" + RUSTFS.getHost()
                + ":" + RUSTFS.getMappedPort(9000));
        client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3ObjectStorage(client, new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        endpoint, "us-east-1", BUCKET,
                        ACCESS_KEY, SECRET_KEY, true)));
    }

    @Test
    void 应通过标准S3接口完成上传元数据范围读取和删除() throws Exception {
        byte[] content = "rustfs-standard-s3".getBytes(StandardCharsets.UTF_8);
        String objectKey = "contract/sample.txt";

        storage.put(objectKey, new ByteArrayInputStream(content),
                content.length, "text/plain");

        assertThat(storage.head(objectKey))
                .extracting(ObjectStorage.ObjectMetadata::contentLength,
                        ObjectStorage.ObjectMetadata::contentType)
                .containsExactly((long) content.length, "text/plain");
        try (ObjectStorage.ObjectContent range = storage.get(
                objectKey, new ObjectStorage.ByteRange(7, 14))) {
            assertThat(range.inputStream().readAllBytes())
                    .isEqualTo("standard".getBytes(StandardCharsets.UTF_8));
            assertThat(range.contentRange())
                    .isEqualTo("bytes 7-14/" + content.length);
        }

        storage.delete(objectKey);
        assertThatThrownBy(() -> storage.head(objectKey))
                .isInstanceOf(ObjectStorageException.class);
    }
}
