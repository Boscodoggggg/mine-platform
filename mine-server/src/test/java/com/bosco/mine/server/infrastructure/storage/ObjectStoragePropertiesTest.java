package com.bosco.mine.server.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验通用对象存储配置的供应商中立边界。
 */
class ObjectStoragePropertiesTest {

    /** 合法 S3 配置应保留标准连接参数。 */
    @Test
    void 应规范化合法S3配置() {
        ObjectStorageProperties properties = properties("mine-scene-assets");

        assertThat(properties.type()).isEqualTo(ObjectStorageProperties.StorageType.S3);
        assertThat(properties.s3().endpoint()).isEqualTo(URI.create("http://rustfs:9000"));
        assertThat(properties.s3().region()).isEqualTo("us-east-1");
        assertThat(properties.s3().bucket()).isEqualTo("mine-scene-assets");
        assertThat(properties.s3().pathStyleAccess()).isTrue();
    }

    /** S3 bucket 不能为空或包含非法字符。 */
    @Test
    void 应拒绝非法Bucket() {
        assertThatThrownBy(() -> properties(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
        assertThatThrownBy(() -> properties("Mine_Bucket"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    /** 自定义 endpoint 只能使用 HTTP 或 HTTPS。 */
    @Test
    void 应拒绝非Http端点() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("file:///tmp/storage"), "us-east-1", "mine-assets",
                        "access", "secret", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }

    /** 区域和访问凭据缺失时应在启动阶段失败。 */
    @Test
    void 应拒绝缺失连接身份() {
        assertThatThrownBy(() -> new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("http://rustfs:9000"), " ", "mine-assets",
                        "access", "secret", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("region");
        assertThatThrownBy(() -> new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("http://rustfs:9000"), "us-east-1", "mine-assets",
                        "", "secret", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-key");
    }

    private ObjectStorageProperties properties(String bucket) {
        return new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("http://rustfs:9000"), "us-east-1", bucket,
                        "access", "secret", true));
    }
}
