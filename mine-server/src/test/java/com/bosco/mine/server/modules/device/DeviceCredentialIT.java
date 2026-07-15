package com.bosco.mine.server.modules.device;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.device.dto.DeviceCredentialResponse;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.service.DeviceCommandService;
import com.bosco.mine.server.modules.device.service.DeviceCredentialService;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL、RabbitMQ 管理接口和 MQTT 客户端校验设备凭据闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeviceCredentialIT {

    private static final String ADMIN_USERNAME = "mine-test-admin";
    private static final String ADMIN_PASSWORD = "mine-test-admin-password";
    private static final Path ADMIN_USERNAME_FILE = temporarySecretFile("rabbit-user", ADMIN_USERNAME);
    private static final Path ADMIN_PASSWORD_FILE = temporarySecretFile("rabbit-password", ADMIN_PASSWORD);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.1.8-management")
            .withAdminUser(ADMIN_USERNAME)
            .withAdminPassword(ADMIN_PASSWORD)
            .withExposedPorts(5672, 15672, 1883)
            .withRabbitMQConfigSysctl(MountableFile.forClasspathResource("rabbitmq/rabbitmq.conf"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("rabbitmq/enabled_plugins"),
                    "/etc/rabbitmq/enabled_plugins"
            );

    @Autowired
    private DeviceCommandService commandService;

    @Autowired
    private DeviceCredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void 注册RabbitMQ管理接口配置(DynamicPropertyRegistry registry) {
        registry.add("mine.rabbitmq.admin.base-url", RABBITMQ::getHttpUrl);
        registry.add("mine.rabbitmq.admin.username-file", ADMIN_USERNAME_FILE::toString);
        registry.add("mine.rabbitmq.admin.password-file", ADMIN_PASSWORD_FILE::toString);
        registry.add("mine.rabbitmq.admin.vhost", () -> "/mine-device");
    }

    @BeforeAll
    static void 创建设备虚拟主机() throws Exception {
        ExecResult result = RABBITMQ.execInContainer(
                "rabbitmqctl", "add_vhost", "/mine-device"
        );
        assertThat(result.getExitCode()).isZero();
    }

    @BeforeEach
    void 清理设备凭据测试数据() throws Exception {
        for (String username : jdbcTemplate.queryForList(
                "SELECT username FROM dev_device_credential", String.class
        )) {
            RABBITMQ.execInContainer("rabbitmqctl", "delete_user", username);
        }
        jdbcTemplate.update("DELETE FROM dev_device_credential");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    @Test
    void 签发时只返回一次明文且数据库只保存散列() throws Exception {
        DeviceResponse device = createDevice("credential-once");

        DeviceCredentialResponse issued = credentialService.issue(device.id());

        assertThat(issued.secret()).isNotBlank();
        assertThat(issued.clientId()).isEqualTo(issued.username());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT secret_hash FROM dev_device_credential WHERE id = ?",
                String.class,
                issued.id()
        )).startsWith("$2").doesNotContain(issued.secret());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'dev_device_credential' AND column_name IN ('secret', 'password')",
                Long.class
        )).isZero();
    }

    @Test
    void 设备账号只能访问自己的MQTT主题() throws Exception {
        DeviceCredentialResponse first = credentialService.issue(createDevice("topic-a").id());
        DeviceCredentialResponse second = credentialService.issue(createDevice("topic-b").id());
        DeviceCredentialResponse subscribeActor = credentialService.issue(
                createDevice("topic-subscribe-denied").id()
        );

        MqttClient allowed = connect(first, true);
        try {
            allowed.subscribe(first.downTopic() + "/command", 1);
            allowed.publish(
                    first.upTopic() + "/telemetry",
                    "{}".getBytes(StandardCharsets.UTF_8),
                    1,
                    false
            );
        } finally {
            close(allowed);
        }

        MqttClient denied = connect(first, true);
        try {
            assertThatThrownBy(() -> denied.publish(
                    second.upTopic() + "/telemetry",
                    "{}".getBytes(StandardCharsets.UTF_8),
                    1,
                    false
            )).isInstanceOf(MqttException.class);
        } finally {
            close(denied);
        }

        MqttClient wrongProduct = connect(subscribeActor, true);
        try {
            assertThatThrownBy(() -> wrongProduct.publish(
                    "mine/v1/wrong-product/" + subscribeActor.clientId() + "/up/telemetry",
                    "{}".getBytes(StandardCharsets.UTF_8),
                    1,
                    false
            )).isInstanceOf(MqttException.class);
        } finally {
            close(wrongProduct);
        }

        DeviceCredentialResponse subscribeOnlyActor = credentialService.issue(
                createDevice("topic-subscribe-only").id()
        );
        MqttClient subscribeDenied = connect(subscribeOnlyActor, true);
        try {
            assertThatThrownBy(() -> subscribeDenied.subscribe(
                    second.downTopic() + "/command", 1
            )).isInstanceOf(MqttException.class);
        } finally {
            close(subscribeDenied);
        }
    }

    @Test
    void 轮换凭据后旧密钥应失效且新密钥可连接() throws Exception {
        DeviceCredentialResponse issued = credentialService.issue(createDevice("rotate").id());
        MqttClient original = connect(issued, true);
        try {
            DeviceCredentialResponse rotated = credentialService.rotate(issued.id(), issued.version());

            assertThat(rotated.secret()).isNotEqualTo(issued.secret());
            assertThatThrownBy(() -> original.publish(
                    issued.upTopic() + "/telemetry",
                    "{}".getBytes(StandardCharsets.UTF_8),
                    1,
                    false
            )).isInstanceOf(MqttException.class);
            assertThatThrownBy(() -> connect(issued, true)).isInstanceOf(MqttException.class);
            MqttClient current = connect(rotated, true);
            try {
                assertThat(current.isConnected()).isTrue();
            } finally {
                close(current);
            }
        } finally {
            close(original);
        }
    }

    @Test
    void 吊销凭据后账号应不能再连接() throws Exception {
        DeviceCredentialResponse issued = credentialService.issue(createDevice("revoke").id());

        credentialService.revoke(issued.id(), issued.version());

        assertThatThrownBy(() -> connect(issued, true)).isInstanceOf(MqttException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM dev_device_credential WHERE id = ?",
                String.class,
                issued.id()
        )).isEqualTo("REVOKED");
    }

    private DeviceResponse createDevice(String suffix) {
        ProductResponse product = commandService.createProduct(
                "product-" + suffix, "测试产品" + suffix, "SENSOR", "ACTIVE"
        );
        return commandService.createDevice(
                product.id(), "device:" + suffix, "测试设备" + suffix, "ENABLED"
        );
    }

    private MqttClient connect(DeviceCredentialResponse credential, boolean cleanSession) throws MqttException {
        MqttClient client = new MqttClient(
                "tcp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(1883),
                credential.clientId(),
                new MemoryPersistence()
        );
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setUserName(credential.username());
        options.setPassword(credential.secret().toCharArray());
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(5);
        client.connect(options);
        return client;
    }

    private void close(MqttClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect(1000L);
            }
            client.close(true);
        } catch (MqttException ignored) {
            // Broker 拒绝越权操作时会主动断开，清理失败不覆盖业务断言。
        }
    }

    private static Path temporarySecretFile(String prefix, String value) {
        try {
            Path file = Files.createTempFile(prefix, ".secret");
            Files.writeString(file, value, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建设备凭据测试密钥文件", exception);
        }
    }
}
