package com.bosco.mine.messaging;

import com.bosco.mine.messaging.topology.MineTopology;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
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
                        MineTopology.DEVICE_VHOST + "\tquorum",
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
                        MineTopology.DEAD_LETTER_EXCHANGE
                );

        String queues = rabbitmqctl(
                "list_queues", "-p", MineTopology.PLATFORM_VHOST,
                "name", "durable", "arguments"
        );
        assertThat(queues)
                .contains(
                        MineTopology.TELEMETRY_QUEUE,
                        MineTopology.COMMAND_QUEUE,
                        MineTopology.CONFIG_QUEUE,
                        MineTopology.DEAD_LETTER_QUEUE
                );
        assertThat(lineStartingWith(queues, MineTopology.TELEMETRY_QUEUE))
                .contains(
                        "\ttrue\t",
                        "{\"x-queue-type\",\"quorum\"}",
                        "{\"x-dead-letter-exchange\",\"mine.dlx\"}"
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
                        MineTopology.COMMAND_EXCHANGE + "\t"
                                + MineTopology.COMMAND_QUEUE + "\t"
                                + MineTopology.COMMAND_ROUTING_PATTERN,
                        MineTopology.CONFIG_EXCHANGE + "\t"
                                + MineTopology.CONFIG_QUEUE + "\t"
                                + MineTopology.CONFIG_ROUTING_PATTERN
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
