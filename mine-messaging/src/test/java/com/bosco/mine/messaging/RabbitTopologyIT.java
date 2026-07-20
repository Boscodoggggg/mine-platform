package com.bosco.mine.messaging;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.messaging.topology.MqttDevicePermissions;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 RabbitMQ 校验 MQTT 插件、双虚拟主机、内部拓扑和设备账号隔离。
 */
@Testcontainers
class RabbitTopologyIT {

    private static final String DEVICE_USERNAME = "mine-device-test";
    private static final String DEVICE_PASSWORD = "mine-device-test-password";
    private static final String MQTT_DEVICE_A = "device-a";
    private static final String MQTT_DEVICE_B = "device-b";
    private static final String MQTT_PUBLISH_DENIED_DEVICE = "device-publish-denied";
    private static final String MQTT_ACTION_DENIED_DEVICE = "device-action-denied";
    private static final String MQTT_SUBSCRIBE_DENIED_DEVICE = "device-subscribe-denied";
    private static final String MQTT_PASSWORD = "mqtt-device-test-password";
    private static final Path RABBITMQ_CONFIG = rabbitmqConfigDirectory();

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.1.8-management")
    )
            .withExposedPorts(5672, 15672, 15692, 1883)
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

    @Test
    void 应启用MQTT插件并监听MQTT端口() throws Exception {
        ExecResult plugins = RABBITMQ.execInContainer(
                "rabbitmq-plugins", "list", "-e", "-m"
        );
        assertThat(plugins.getExitCode()).isZero();
        assertThat(plugins.getStdout()).contains("rabbitmq_mqtt", "rabbitmq_prometheus");

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(RABBITMQ.getHost(), RABBITMQ.getMappedPort(1883)),
                    (int) Duration.ofSeconds(5).toMillis()
            );
            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void 应加载双虚拟主机和平台内部拓扑() throws Exception {
        String vhosts = rabbitmqctl("list_vhosts", "name", "default_queue_type");
        assertThat(vhosts)
                .contains(
                        MineTopology.DEVICE_VHOST + "\tclassic",
                        MineTopology.PLATFORM_VHOST + "\tquorum"
                );

        String exchanges = rabbitmqctl(
                "list_exchanges", "-p", MineTopology.PLATFORM_VHOST,
                "name", "type", "durable"
        );
        assertThat(exchanges)
                .contains(
                        MineTopology.TELEMETRY_EXCHANGE,
                        MineTopology.COMMAND_EXCHANGE,
                        MineTopology.CONFIG_EXCHANGE,
                        MineTopology.REALTIME_EXCHANGE,
                        MineTopology.LOCATION_CONFIG_EXCHANGE,
                        MineTopology.BUSINESS_EXCHANGE,
                        MineTopology.DEAD_LETTER_EXCHANGE
                );

        String queues = rabbitmqctl(
                "list_queues", "-p", MineTopology.PLATFORM_VHOST,
                "name", "durable", "arguments"
        );
        assertThat(queues)
                .contains(
                        MineTopology.TELEMETRY_QUEUE,
                        MineTopology.LOCATION_QUEUE,
                        MineTopology.COMMAND_QUEUE,
                        MineTopology.COMMAND_STATUS_QUEUE,
                        MineTopology.COMMAND_TIMEOUT_WAIT_QUEUE,
                        MineTopology.CONFIG_QUEUE,
                        MineTopology.TELEMETRY_LOCATION_CONFIG_QUEUE,
                        MineTopology.SERVER_ALARM_EVENT_QUEUE,
                        MineTopology.SERVER_OPERATION_SPATIAL_QUEUE,
                        MineTopology.DEAD_LETTER_QUEUE
                );
        assertThat(lineStartingWith(queues, MineTopology.TELEMETRY_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
                );
        assertThat(lineStartingWith(queues, MineTopology.LOCATION_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
                );
        assertThat(lineStartingWith(queues, MineTopology.TELEMETRY_LOCATION_CONFIG_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
                );
        assertThat(lineStartingWith(queues, MineTopology.SERVER_ALARM_EVENT_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
                );
        assertThat(lineStartingWith(queues, MineTopology.SERVER_OPERATION_SPATIAL_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
                );
        assertThat(lineStartingWith(queues, MineTopology.DEAD_LETTER_QUEUE))
                .contains(
                        "{\"x-max-length-bytes\",1073741824}",
                        "{\"x-overflow\",\"drop-head\"}"
                );

        String bindings = rabbitmqctl(
                "list_bindings", "-p", MineTopology.PLATFORM_VHOST,
                "source_name", "destination_name", "routing_key"
        );
        assertThat(bindings)
                .contains(
                        MineTopology.TELEMETRY_EXCHANGE + "\t"
                                + MineTopology.TELEMETRY_QUEUE + "\t"
                                + MineTopology.TELEMETRY_ROUTING_PATTERN,
                        MineTopology.TELEMETRY_EXCHANGE + "\t"
                                + MineTopology.LOCATION_QUEUE + "\t"
                                + MineTopology.LOCATION_OBSERVATION_ROUTING_KEY,
                        MineTopology.COMMAND_EXCHANGE + "\t"
                                + MineTopology.COMMAND_QUEUE + "\t"
                                + MineTopology.COMMAND_DISPATCH_ROUTING_PATTERN,
                        MineTopology.COMMAND_EXCHANGE + "\t"
                                + MineTopology.COMMAND_STATUS_QUEUE + "\t"
                                + MineTopology.COMMAND_STATUS_ROUTING_PATTERN,
                        MineTopology.COMMAND_EXCHANGE + "\t"
                                + MineTopology.COMMAND_TIMEOUT_WAIT_QUEUE + "\t"
                                + "command.timeout.schedule",
                        MineTopology.CONFIG_EXCHANGE + "\t"
                                + MineTopology.CONFIG_QUEUE + "\t"
                                + MineTopology.CONFIG_ROUTING_PATTERN,
                        MineTopology.LOCATION_CONFIG_EXCHANGE + "\t"
                                + MineTopology.TELEMETRY_LOCATION_CONFIG_QUEUE + "\t"
                                + MineTopology.LOCATION_RUNTIME_ROUTING_PATTERN,
                        MineTopology.BUSINESS_EXCHANGE + "\t"
                                + MineTopology.SERVER_ALARM_EVENT_QUEUE + "\t"
                                + MineTopology.ALARM_EVENT_ROUTING_PATTERN,
                        MineTopology.REALTIME_EXCHANGE + "\t"
                                + MineTopology.SERVER_OPERATION_SPATIAL_QUEUE + "\t"
                                + MineTopology.REALTIME_SPATIAL_ROUTING_PATTERN
                );

        String deviceQueues = rabbitmqctl(
                "list_queues", "-p", MineTopology.DEVICE_VHOST,
                "name", "durable", "arguments"
        );
        assertThat(deviceQueues).contains(
                MineTopology.DEVICE_UPLINK_QUEUE,
                MineTopology.DEVICE_LOCATION_UPLINK_QUEUE,
                MineTopology.DEVICE_COMMAND_ACK_QUEUE
        );
        String deviceBindings = rabbitmqctl(
                "list_bindings", "-p", MineTopology.DEVICE_VHOST,
                "source_name", "destination_name", "routing_key"
        );
        assertThat(deviceBindings).contains(
                MineTopology.MQTT_EXCHANGE + "\t"
                        + MineTopology.DEVICE_LOCATION_UPLINK_QUEUE + "\t"
                        + MineTopology.DEVICE_LOCATION_ROUTING_PATTERN,
                MineTopology.MQTT_EXCHANGE + "\t"
                        + MineTopology.DEVICE_COMMAND_ACK_QUEUE + "\t"
                        + MineTopology.DEVICE_COMMAND_ACK_ROUTING_PATTERN
        );
    }

    @Test
    void 设备账号只允许访问设备虚拟主机() throws Exception {
        rabbitmqctl("add_user", DEVICE_USERNAME, DEVICE_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST,
                DEVICE_USERNAME, ".*", ".*", ".*"
        );

        try (Connection ignored = openConnection(MineTopology.DEVICE_VHOST)) {
            assertThat(ignored.isOpen()).isTrue();
        }
        assertThatThrownBy(() -> openConnection(MineTopology.PLATFORM_VHOST))
                .isInstanceOfAny(IOException.class, TimeoutException.class);
    }

    @Test
    void 不应保留默认Guest账号() throws Exception {
        assertThat(rabbitmqctl("list_users")).doesNotContain("guest");
    }

    @Test
    void MQTT设备应只能发布和订阅本设备主题() throws Exception {
        createMqttDevice(MQTT_DEVICE_A);
        createMqttDevice(MQTT_DEVICE_B);
        createMqttDevice(MQTT_PUBLISH_DENIED_DEVICE);
        createMqttDevice(MQTT_ACTION_DENIED_DEVICE);
        createMqttDevice(MQTT_SUBSCRIBE_DENIED_DEVICE);

        MqttClient deviceA = openMqttClient(MQTT_DEVICE_A, false);
        try {
            deviceA.subscribe("mine/v1/truck/device-a/down/command", 1);
            deviceA.publish(
                    "mine/v1/truck/device-a/up/telemetry",
                    "{\"speed\":12}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    1,
                    false
            );
            deviceA.publish(
                    "mine/v1/truck/device-a/up/location",
                    "{\"x\":12,\"y\":18}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    1,
                    false
            );
            deviceA.publish(
                    "mine/v1/truck/device-a/up/command-ack",
                    "{\"status\":\"SUCCEEDED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    1,
                    false
            );
        } finally {
            closeMqttClient(deviceA);
        }

        String queues = rabbitmqctl(
                "list_queues", "-p", MineTopology.DEVICE_VHOST,
                "name", "type"
        );
        assertThat(lineStartingWith(queues, "mqtt-subscription-device-aqos1"))
                .endsWith("\tclassic");

        // Broker 拒绝越权后会异步断开连接；每个断言使用独立 clientId，避免旧连接清理与重连互踢。
        MqttClient publishDenied = openMqttClient(MQTT_PUBLISH_DENIED_DEVICE, true);
        try {
            assertThatThrownBy(() -> publishDenied.publish(
                    "mine/v1/truck/device-b/up/telemetry",
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    1,
                    false
            )).isInstanceOf(MqttException.class);
        } finally {
            closeMqttClient(publishDenied);
        }

        MqttClient actionDenied = openMqttClient(MQTT_ACTION_DENIED_DEVICE, true);
        try {
            assertThatThrownBy(() -> actionDenied.publish(
                    "mine/v1/truck/device-action-denied/up/event",
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    1,
                    false
            )).isInstanceOf(MqttException.class);
        } finally {
            closeMqttClient(actionDenied);
        }

        MqttClient subscribeDenied = openMqttClient(MQTT_SUBSCRIBE_DENIED_DEVICE, true);
        try {
            assertThatThrownBy(() -> subscribeDenied.subscribe(
                    "mine/v1/truck/device-b/down/command", 1
            )).isInstanceOf(MqttException.class);
        } finally {
            closeMqttClient(subscribeDenied);
        }

    }

    private Connection openConnection(String virtualHost) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ.getHost());
        factory.setPort(RABBITMQ.getAmqpPort());
        factory.setUsername(DEVICE_USERNAME);
        factory.setPassword(DEVICE_PASSWORD);
        factory.setVirtualHost(virtualHost);
        factory.setConnectionTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setHandshakeTimeout((int) Duration.ofSeconds(5).toMillis());
        return factory.newConnection("rabbit-topology-it");
    }

    private void createMqttDevice(String deviceId) throws Exception {
        rabbitmqctl("add_user", deviceId, MQTT_PASSWORD);
        MqttDevicePermissions permissions = MqttDevicePermissions.forDevice(deviceId);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST,
                deviceId,
                permissions.configurePattern(),
                permissions.writePattern(),
                permissions.readPattern()
        );
        rabbitmqctl(
                "set_topic_permissions", "-p", MineTopology.DEVICE_VHOST,
                deviceId, MineTopology.MQTT_EXCHANGE,
                permissions.topicWritePattern(),
                permissions.topicReadPattern()
        );
    }

    private MqttClient openMqttClient(String deviceId, boolean cleanSession) throws MqttException {
        MqttClient client = new MqttClient(
                "tcp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(1883),
                deviceId,
                new MemoryPersistence()
        );
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setUserName(deviceId);
        options.setPassword(MQTT_PASSWORD.toCharArray());
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(10);
        client.connect(options);
        return client;
    }

    private void closeMqttClient(MqttClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect(1000L);
            }
        } catch (MqttException ignored) {
            // Broker 拒绝越权操作时会主动断开；清理阶段不得覆盖权限断言结果。
        } finally {
            try {
                client.close(true);
            } catch (MqttException ignored) {
                // 客户端可能仍处于异步断开状态，强制关闭失败无需再次抛出。
            }
        }
    }

    private String rabbitmqctl(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);

        ExecResult result = RABBITMQ.execInContainer(command);
        assertThat(result.getExitCode())
                .as("rabbitmqctl %s 执行失败：%s", String.join(" ", arguments), result.getStderr())
                .isZero();
        return result.getStdout();
    }

    private String lineStartingWith(String content, String prefix) {
        return content.lines()
                .filter(line -> line.startsWith(prefix + "\t"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 RabbitMQ 条目：" + prefix));
    }

    private static Path rabbitmqConfigDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("deploy/config/rabbitmq");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 deploy/config/rabbitmq 目录");
    }
}
