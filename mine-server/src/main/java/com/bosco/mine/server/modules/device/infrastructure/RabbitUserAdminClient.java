package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.messaging.topology.MqttDevicePermissions;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 通过 RabbitMQ HTTP 管理接口维护设备用户、vhost 权限和主题权限。
 */
@Component
public class RabbitUserAdminClient {

    private final RabbitAdminProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建设备用户管理客户端。
     *
     * @param properties   管理接口配置
     * @param objectMapper JSON 序列化器
     */
    public RabbitUserAdminClient(RabbitAdminProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建或覆盖设备用户，并设置最小化 MQTT 资源和主题权限。
     *
     * @param productKey 设备所属产品编码
     * @param username   设备用户名
     * @param secret     设备明文密钥，仅用于本次管理请求
     */
    public void provision(String productKey, String username, String secret) {
        MqttDevicePermissions permissions = MqttDevicePermissions.forDevice(productKey, username);
        put("/api/users/" + encode(username), Map.of("password", secret, "tags", ""));
        try {
            put("/api/permissions/" + encode(properties.vhost()) + "/" + encode(username), Map.of(
                    "configure", permissions.configurePattern(),
                    "write", permissions.writePattern(),
                    "read", permissions.readPattern()
            ));
            put("/api/topic-permissions/" + encode(properties.vhost()) + "/" + encode(username), Map.of(
                    "exchange", MineTopology.MQTT_EXCHANGE,
                    "write", permissions.topicWritePattern(),
                    "read", permissions.topicReadPattern()
            ));
        } catch (RuntimeException exception) {
            deleteQuietly(username);
            throw exception;
        }
    }

    /**
     * 立即替换设备用户密码，既有连接断开后只能使用新密钥重连。
     *
     * @param username 设备用户名
     * @param secret   新明文密钥
     */
    public void changePassword(String username, String secret) {
        put("/api/users/" + encode(username), Map.of("password", secret, "tags", ""));
        disconnect(username);
    }

    /**
     * 删除设备 Broker 用户并撤销全部访问能力。
     *
     * @param username 设备用户名
     */
    public void delete(String username) {
        disconnect(username);
        request("DELETE", "/api/users/" + encode(username), null, 204);
    }

    /**
     * 主动关闭该用户的全部现有连接，使密码轮换和吊销立即作用于在线会话。
     *
     * @param username 设备用户名
     */
    public void disconnect(String username) {
        request("DELETE", "/api/connections/username/" + encode(username), null, 204);
    }

    /**
     * 在补偿流程中尽力删除设备用户，不覆盖原始业务异常。
     *
     * @param username 设备用户名
     */
    public void deleteQuietly(String username) {
        try {
            delete(username);
        } catch (RuntimeException ignored) {
            // 补偿失败由 Broker 管理审计发现，不能覆盖导致补偿的原始异常。
        }
    }

    private void put(String path, Map<String, ?> body) {
        request("PUT", path, toJson(body), 201, 204);
    }

    private void request(String method, String path, String body, int... expectedStatuses) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                    .timeout(orDefault(properties.requestTimeout(), Duration.ofSeconds(10)))
                    .header("Accept", "application/json")
                    .header("Authorization", basicAuthorization());
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<Void> response = httpClient().send(
                    builder.build(), HttpResponse.BodyHandlers.discarding()
            );
            for (int expectedStatus : expectedStatuses) {
                if (response.statusCode() == expectedStatus) {
                    return;
                }
            }
            throw new IllegalStateException(
                    "RabbitMQ 管理请求失败：method=" + method + "，status=" + response.statusCode()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("RabbitMQ 管理接口不可达", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ 管理请求被中断", exception);
        }
    }

    private URI resolve(String path) {
        String base = properties.baseUrl().toString().replaceFirst("/+$", "");
        return URI.create(base + path);
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(orDefault(properties.connectTimeout(), Duration.ofSeconds(5)))
                .build();
    }

    private String basicAuthorization() {
        String username = readSecret(properties.usernameFile(), "RabbitMQ 管理员用户名");
        String password = readSecret(properties.passwordFile(), "RabbitMQ 管理员密码");
        String value = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Map<String, ?> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法生成 RabbitMQ 管理请求", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String readSecret(java.nio.file.Path path, String description) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException(description + "不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取" + description + "文件", exception);
        }
    }

    private Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
