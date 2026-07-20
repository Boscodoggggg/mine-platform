package com.bosco.mine.server.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验标准 S3 对象存储适配器的请求和响应契约。
 */
class S3ObjectStorageTest {

    /** 写入请求应使用配置 bucket、对象键和媒体类型。 */
    @Test
    void 应使用标准S3参数写入对象() {
        S3Client client = mock(S3Client.class);
        S3ObjectStorage storage = new S3ObjectStorage(client, properties());
        byte[] content = "scene".getBytes(StandardCharsets.UTF_8);

        storage.put("scene/abc/model.glb", new ByteArrayInputStream(content),
                content.length, "model/gltf-binary");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("mine-scene-assets");
        assertThat(request.getValue().key()).isEqualTo("scene/abc/model.glb");
        assertThat(request.getValue().contentLength()).isEqualTo(content.length);
        assertThat(request.getValue().contentType()).isEqualTo("model/gltf-binary");
    }

    /** 单范围读取应发送标准 Range 并保留响应元数据。 */
    @Test
    void 应按单范围读取对象() throws Exception {
        S3Client client = mock(S3Client.class);
        byte[] content = "cene".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(4L)
                .contentType("model/gltf-binary")
                .eTag("etag-1")
                .contentRange("bytes 1-4/5")
                .build();
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(response,
                        new ByteArrayInputStream(content)));
        S3ObjectStorage storage = new S3ObjectStorage(client, properties());

        try (ObjectStorage.ObjectContent result = storage.get(
                "scene/abc/model.glb", new ObjectStorage.ByteRange(1, 4))) {
            assertThat(result.inputStream().readAllBytes()).isEqualTo(content);
            assertThat(result.metadata().contentLength()).isEqualTo(4);
            assertThat(result.metadata().etag()).isEqualTo("etag-1");
            assertThat(result.contentRange()).isEqualTo("bytes 1-4/5");
        }

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=1-4");
    }

    /** HEAD 应映射标准对象元数据。 */
    @Test
    void 应查询对象元数据() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(12L)
                        .contentType("application/json")
                        .eTag("etag-2")
                        .build());

        ObjectStorage.ObjectMetadata metadata = new S3ObjectStorage(client, properties())
                .head("scene/abc/tileset.json");

        assertThat(metadata.contentLength()).isEqualTo(12);
        assertThat(metadata.contentType()).isEqualTo("application/json");
        assertThat(metadata.etag()).isEqualTo("etag-2");
    }

    /** SDK 异常应转换成不泄露 endpoint 和对象键的固定异常。 */
    @Test
    void 应隐藏S3服务端细节() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                NoSuchKeyException.builder().message("secret endpoint/key").build());

        assertThatThrownBy(() -> new S3ObjectStorage(client, properties())
                .head("scene/private/key"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("对象元数据查询失败")
                .hasMessageNotContaining("private")
                .hasMessageNotContaining("endpoint");
    }

    private ObjectStorageProperties properties() {
        return new ObjectStorageProperties(
                ObjectStorageProperties.StorageType.S3,
                new ObjectStorageProperties.S3Properties(
                        URI.create("http://rustfs:9000"), "us-east-1",
                        "mine-scene-assets", "access", "secret", true));
    }
}
