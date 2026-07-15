package com.bosco.mine.gateway.mqtt;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.normalize.DeviceMessageNormalizer;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.messaging.topology.MqttDevicePermissions;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 RabbitMQ 验证 MQTT 设备上报跨 vhost 转换为平台内部遥测事件。
 */
@Testcontainers
class DeviceMessageConsumerIT {

    private static final String DEVICE_CLIENT_ID = "device-credential-001";
    private static final String DEVICE_ID = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final String DEVICE_PASSWORD = "device-password-001";
    private static final String DEVICE_CONSUMER = "gateway-device-consumer";
    private static final String DEVICE_CONSUMER_PASSWORD = "gateway-device-password";
    private static final String PLATFORM_PUBLISHER = "gateway-platform-publisher";
    private static final String PLATFORM_PUBLISHER_PASSWORD = "gateway-platform-password";
    private static final String PLATFORM_OBSERVER = "gateway-platform-observer";
    private static final String PLATFORM_OBSERVER_PASSWORD = "gateway-observer-password";
    private static final Path RABBITMQ_CONFIG = rabbitmqConfigDirectory();

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.1.8-management")
    )
            .withExposedPorts(5672, 15672, 1883)
            .withRabbitMQConfigSysctl(
                    MountableFile.forHostPath(RABBITMQ_CONFIG.resolve("rabbitmq.conf"))
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath(RABBITMQ_CONFIG.resolve("enabled_plugins")),
                    "/etc/rabbitmq/enabled_plugins"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath(RABBITMQ_CONFIG.resolve("definitions.json")),
                    "/etc/rabbitmq/definitions.json"
            );

    private SimpleMessageListenerContainer listener;
    private CachingConnectionFactory deviceConnectionFactory;
    private CachingConnectionFactory platformConnectionFactory;
    private CachingConnectionFactory observerConnectionFactory;

    @BeforeAll
    static void provisionUsers() throws Exception {
        addUser(DEVICE_CLIENT_ID, DEVICE_PASSWORD);
        MqttDevicePermissions permissions = MqttDevicePermissions.forDevice("haul-truck", DEVICE_CLIENT_ID);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST, DEVICE_CLIENT_ID,
                permissions.configurePattern(), permissions.writePattern(), permissions.readPattern()
        );
        rabbitmqctl(
                "set_topic_permissions", "-p", MineTopology.DEVICE_VHOST, DEVICE_CLIENT_ID,
                MineTopology.MQTT_EXCHANGE,
                permissions.topicWritePattern(), permissions.topicReadPattern()
        );

        addUser(DEVICE_CONSUMER, DEVICE_CONSUMER_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST, DEVICE_CONSUMER,
                "^$", "^$", "^" + MineTopology.DEVICE_UPLINK_QUEUE.replace(".", "\\.") + "$"
        );

        addUser(PLATFORM_PUBLISHER, PLATFORM_PUBLISHER_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.PLATFORM_VHOST, PLATFORM_PUBLISHER,
                "^$", "^mine\\.telemetry\\.x$", "^$"
        );

        addUser(PLATFORM_OBSERVER, PLATFORM_OBSERVER_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.PLATFORM_VHOST, PLATFORM_OBSERVER,
                "^$", "^$", "^mine\\.telemetry\\.q$"
        );
    }

    @AfterEach
    void closeConnections() {
        if (listener != null) listener.destroy();
        if (deviceConnectionFactory != null) deviceConnectionFactory.destroy();
        if (platformConnectionFactory != null) platformConnectionFactory.destroy();
        if (observerConnectionFactory != null) observerConnectionFactory.destroy();
    }

    @Test
    void MQTT遥测应经设备队列标准化并发布到平台遥测队列() throws Exception {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck",
                DEVICE_CLIENT_ID,
                DEVICE_ID,
                "simulator-001",
                "mine-site-001",
                "mqtt-json-v1"
        )));
        DeviceMessageNormalizer normalizer = new DeviceMessageNormalizer(
                registry,
                IdGenerator.timeOrdered(),
                Clock.systemUTC(),
                64 * 1024
        );

        deviceConnectionFactory = connectionFactory(
                MineTopology.DEVICE_VHOST, DEVICE_CONSUMER, DEVICE_CONSUMER_PASSWORD, false
        );
        platformConnectionFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, PLATFORM_PUBLISHER, PLATFORM_PUBLISHER_PASSWORD, true
        );
        RabbitTemplate platformTemplate = new RabbitTemplate(platformConnectionFactory);
        DeviceMessageConsumer consumer = new DeviceMessageConsumer(
                normalizer,
                new ConfirmedTelemetryPublisher(platformTemplate, 5000)
        );
        listener = new SimpleMessageListenerContainer(deviceConnectionFactory);
        listener.setQueueNames(MineTopology.DEVICE_UPLINK_QUEUE);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setPrefetchCount(10);
        listener.setMessageListener(consumer);
        listener.start();

        MqttClient mqttClient = mqttClient();
        try {
            mqttClient.publish(
                    "mine/v1/haul-truck/" + DEVICE_CLIENT_ID + "/up/telemetry",
                    payload(),
                    1,
                    false
            );
        } finally {
            if (mqttClient.isConnected()) mqttClient.disconnect(1000);
            mqttClient.close();
        }

        observerConnectionFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, PLATFORM_OBSERVER, PLATFORM_OBSERVER_PASSWORD, false
        );
        RabbitTemplate observer = new RabbitTemplate(observerConnectionFactory);
        observer.setReceiveTimeout(Duration.ofSeconds(10).toMillis());
        Message normalized = observer.receive(MineTopology.TELEMETRY_QUEUE);

        assertThat(normalized).isNotNull();
        JsonNode json = JsonMapper.builder().build().readTree(normalized.getBody());
        assertThat(json.get("eventId").asString()).isEqualTo(json.get("payload").get("messageId").asString());
        assertThat(json.get("payload").get("messageId").asString())
                .satisfies(value -> assertThat(java.util.UUID.fromString(value).version()).isEqualTo(7));
        assertThat(json.get("payload").get("deviceId").asString()).isEqualTo(DEVICE_ID);
        assertThat(json.get("payload").get("properties").get("engine.rpm").asInt()).isEqualTo(1280);
    }

    private static byte[] payload() {
        return """
                {
                  "productKey":"haul-truck",
                  "deviceCode":"simulator-001",
                  "bootId":"simulator-boot-001",
                  "sequence":7,
                  "occurredAt":"2026-07-15T01:59:58Z",
                  "replayed":false,
                  "properties":{"engine.rpm":1280,"vehicle.speed":12.5}
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private MqttClient mqttClient() throws Exception {
        MqttClient client = new MqttClient(
                "tcp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(1883),
                DEVICE_CLIENT_ID,
                new MemoryPersistence()
        );
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(DEVICE_CLIENT_ID);
        options.setPassword(DEVICE_PASSWORD.toCharArray());
        options.setCleanSession(true);
        options.setConnectionTimeout(5);
        client.connect(options);
        return client;
    }

    private CachingConnectionFactory connectionFactory(
            String virtualHost,
            String username,
            String password,
            boolean publisherConfirms
    ) {
        CachingConnectionFactory factory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort()
        );
        factory.setVirtualHost(virtualHost);
        factory.setUsername(username);
        factory.setPassword(password);
        if (publisherConfirms) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        }
        return factory;
    }

    private static void addUser(String username, String password) throws Exception {
        rabbitmqctl("add_user", username, password);
    }

    private static String rabbitmqctl(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        ExecResult result = RABBITMQ.execInContainer(command);
        assertThat(result.getExitCode())
                .as("rabbitmqctl %s 执行失败：%s", String.join(" ", arguments), result.getStderr())
                .isZero();
        return result.getStdout();
    }

    private static Path rabbitmqConfigDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("deploy/config/rabbitmq");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 deploy/config/rabbitmq 目录");
    }
}
