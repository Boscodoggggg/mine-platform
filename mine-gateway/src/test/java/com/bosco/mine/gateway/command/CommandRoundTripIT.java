package com.bosco.mine.gateway.command;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
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
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 RabbitMQ MQTT 插件验证命令下行、设备业务 ACK 与状态事件完整回环。
 */
@Testcontainers
class CommandRoundTripIT {
    private static final String DEVICE_CLIENT_ID = "device-command-001";
    private static final String DEVICE_PASSWORD = "device-command-password";
    private static final String GATEWAY_DEVICE_USER = "gateway-command-device";
    private static final String GATEWAY_DEVICE_PASSWORD = "gateway-command-device-password";
    private static final String GATEWAY_PLATFORM_USER = "gateway-command-platform";
    private static final String GATEWAY_PLATFORM_PASSWORD = "gateway-command-platform-password";
    private static final String STATUS_OBSERVER_USER = "command-status-observer";
    private static final String STATUS_OBSERVER_PASSWORD = "command-status-observer-password";
    private static final UUID DEVICE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000020");
    private static final Path RABBITMQ_CONFIG = rabbitmqConfigDirectory();
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

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

    private final List<AutoCloseable> resources = new ArrayList<>();

    /**
     * 创建测试所需的最小权限账号。
     *
     * @throws Exception RabbitMQ 管理命令失败
     */
    @BeforeAll
    static void createAccounts() throws Exception {
        addUser(DEVICE_CLIENT_ID, DEVICE_PASSWORD);
        MqttDevicePermissions devicePermissions = MqttDevicePermissions.forDevice(DEVICE_CLIENT_ID);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST, DEVICE_CLIENT_ID,
                devicePermissions.configurePattern(), devicePermissions.writePattern(),
                devicePermissions.readPattern()
        );
        rabbitmqctl(
                "set_topic_permissions", "-p", MineTopology.DEVICE_VHOST, DEVICE_CLIENT_ID,
                MineTopology.MQTT_EXCHANGE, devicePermissions.topicWritePattern(),
                devicePermissions.topicReadPattern()
        );

        addUser(GATEWAY_DEVICE_USER, GATEWAY_DEVICE_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.DEVICE_VHOST, GATEWAY_DEVICE_USER,
                "^$", "^amq\\.topic$",
                "^(mine\\.gateway\\.uplink\\.q|mine\\.gateway\\.command-ack\\.q)$"
        );
        addUser(GATEWAY_PLATFORM_USER, GATEWAY_PLATFORM_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.PLATFORM_VHOST, GATEWAY_PLATFORM_USER,
                "^$", "^(mine\\.telemetry\\.x|mine\\.command\\.x)$",
                "^mine\\.gateway\\.command\\.q$"
        );
        addUser(STATUS_OBSERVER_USER, STATUS_OBSERVER_PASSWORD);
        rabbitmqctl(
                "set_permissions", "-p", MineTopology.PLATFORM_VHOST, STATUS_OBSERVER_USER,
                "^$", "^$", "^mine\\.server\\.command-status\\.q$"
        );
    }

    /**
     * 关闭每个测试创建的客户端和连接。
     *
     * @throws Exception 资源关闭失败
     */
    @AfterEach
    void closeResources() throws Exception {
        for (int index = resources.size() - 1; index >= 0; index--) {
            resources.get(index).close();
        }
        resources.clear();
    }

    @Test
    void 平台命令应经MQTT到达设备且业务ACK应形成独立终态事件() throws Exception {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.replace(List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", DEVICE_CLIENT_ID, DEVICE_ID.toString(), "truck-command-001",
                "mine-site-001", "mqtt-json-v1"
        )));
        CommandMessageCodec codec = new CommandMessageCodec(registry, 64 * 1024);
        CachingConnectionFactory deviceFactory = connectionFactory(
                MineTopology.DEVICE_VHOST, GATEWAY_DEVICE_USER, GATEWAY_DEVICE_PASSWORD, true
        );
        CachingConnectionFactory platformFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, GATEWAY_PLATFORM_USER, GATEWAY_PLATFORM_PASSWORD, true
        );
        resources.add(deviceFactory::destroy);
        resources.add(platformFactory::destroy);

        ConfirmedCommandStatusPublisher statusPublisher = new ConfirmedCommandStatusPublisher(
                new RabbitTemplate(platformFactory), IdGenerator.timeOrdered(), Clock.systemUTC(), 5000L
        );
        CommandDispatchConsumer dispatchConsumer = new CommandDispatchConsumer(
                codec,
                new MqttCommandPublisher(new RabbitTemplate(deviceFactory), registry, Clock.systemUTC(), 5000L),
                statusPublisher,
                new RabbitCommandTimeoutScheduler(
                        new RabbitTemplate(platformFactory), IdGenerator.timeOrdered(), Clock.systemUTC(), 5000L
                ),
                Clock.systemUTC()
        );
        CommandAckConsumer ackConsumer = new CommandAckConsumer(codec, statusPublisher);
        SimpleMessageListenerContainer dispatchListener = listener(
                platformFactory, MineTopology.COMMAND_QUEUE, dispatchConsumer
        );
        SimpleMessageListenerContainer ackListener = listener(
                deviceFactory, MineTopology.DEVICE_COMMAND_ACK_QUEUE, ackConsumer
        );
        resources.add(dispatchListener::destroy);
        resources.add(ackListener::destroy);
        dispatchListener.start();
        ackListener.start();

        CountDownLatch deviceAckPublished = new CountDownLatch(1);
        MqttClient device = mqttDevice();
        resources.add(() -> closeMqtt(device));
        device.subscribe("mine/v1/haul-truck/" + DEVICE_CLIENT_ID + "/down/command", 1,
                (topic, mqttMessage) -> {
                    JsonNode command = JSON.readTree(mqttMessage.getPayload());
                    Map<String, Object> ack = Map.of(
                            "commandId", command.get("commandId").asString(),
                            "status", "SUCCEEDED",
                            "occurredAt", Instant.now().toString(),
                            "traceId", command.get("traceId").asString(),
                            "result", Map.of("online", true)
                    );
                    device.publish(
                            "mine/v1/haul-truck/" + DEVICE_CLIENT_ID + "/up/command-ack",
                            JSON.writeValueAsBytes(ack), 1, false
                    );
                    deviceAckPublished.countDown();
                });

        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        Instant now = Instant.now();
        MessageEnvelope<CommandDispatchPayload> dispatch = new MessageEnvelope<>(
                UUID.fromString("019f64e5-a000-7000-8000-000000000001"),
                MessageType.of("command.dispatch.requested"), 1,
                now, now, "mine-server", "trace-command-round-trip",
                commandId.toString(), DEVICE_ID.toString(), "mine-site-001",
                new CommandDispatchPayload(
                        commandId, DEVICE_ID, "QUERY_STATUS", 1, Map.of(), "LOW",
                        300, 30, now.plusSeconds(60)
                )
        );
        RabbitTemplate platform = new RabbitTemplate(platformFactory);
        platform.convertAndSend(
                MineTopology.COMMAND_EXCHANGE,
                "command.dispatch." + DEVICE_ID,
                MessageBuilder.withBody(JSON.writeValueAsBytes(dispatch))
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setContentType("application/json")
                        .build()
        );

        assertThat(deviceAckPublished.await(15, TimeUnit.SECONDS)).isTrue();
        CachingConnectionFactory observerFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, STATUS_OBSERVER_USER, STATUS_OBSERVER_PASSWORD, false
        );
        resources.add(observerFactory::destroy);
        RabbitTemplate observer = new RabbitTemplate(observerFactory);
        Set<String> statuses = Set.of(
                status(observer.receive(MineTopology.COMMAND_STATUS_QUEUE, 15_000L)),
                status(observer.receive(MineTopology.COMMAND_STATUS_QUEUE, 15_000L))
        );
        assertThat(statuses).containsExactlyInAnyOrder("SENT", "SUCCEEDED");
    }

    @Test
    void 回复超时应由RabbitMQ到期路由且无需扫表任务() throws Exception {
        CachingConnectionFactory platformFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, GATEWAY_PLATFORM_USER, GATEWAY_PLATFORM_PASSWORD, true
        );
        CachingConnectionFactory observerFactory = connectionFactory(
                MineTopology.PLATFORM_VHOST, STATUS_OBSERVER_USER, STATUS_OBSERVER_PASSWORD, false
        );
        resources.add(platformFactory::destroy);
        resources.add(observerFactory::destroy);
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000011");
        RabbitCommandTimeoutScheduler scheduler = new RabbitCommandTimeoutScheduler(
                new RabbitTemplate(platformFactory), IdGenerator.timeOrdered(), Clock.systemUTC(), 5000L
        );

        scheduler.schedule(
                commandId, DEVICE_ID, Instant.now().plusMillis(1200L),
                "trace-command-timeout", "mine-site-001"
        );

        Message timeout = new RabbitTemplate(observerFactory)
                .receive(MineTopology.COMMAND_STATUS_QUEUE, 10_000L);
        assertThat(timeout).isNotNull();
        JsonNode envelope = JSON.readTree(timeout.getBody());
        assertThat(envelope.get("payload").get("commandId").asString())
                .isEqualTo(commandId.toString());
        assertThat(envelope.get("payload").get("status").asString()).isEqualTo("TIMEOUT");
        assertThat(envelope.get("payload").get("errorCode").asString())
                .isEqualTo("COMMAND_REPLY_TIMEOUT");
    }

    private String status(Message message) throws Exception {
        assertThat(message).isNotNull();
        JsonNode envelope = JSON.readTree(message.getBody());
        assertThat(envelope.get("eventType").asString()).isEqualTo("command.status.changed");
        assertThat(envelope.get("traceId").asString()).isEqualTo("trace-command-round-trip");
        assertThat(envelope.get("payload").get("commandId").asString())
                .isEqualTo("019f64e5-a000-7000-8000-000000000010");
        return envelope.get("payload").get("status").asString();
    }

    private SimpleMessageListenerContainer listener(
            CachingConnectionFactory factory,
            String queue,
            org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener consumer
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(10);
        container.setDefaultRequeueRejected(false);
        container.setMessageListener(consumer);
        return container;
    }

    private CachingConnectionFactory connectionFactory(
            String vhost,
            String username,
            String password,
            boolean confirms
    ) {
        CachingConnectionFactory factory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort()
        );
        factory.setVirtualHost(vhost);
        factory.setUsername(username);
        factory.setPassword(password);
        if (confirms) factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return factory;
    }

    private MqttClient mqttDevice() throws Exception {
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
        options.setKeepAliveInterval(10);
        client.connect(options);
        return client;
    }

    private void closeMqtt(MqttClient client) throws Exception {
        if (client.isConnected()) client.disconnect(1000L);
        client.close(true);
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
