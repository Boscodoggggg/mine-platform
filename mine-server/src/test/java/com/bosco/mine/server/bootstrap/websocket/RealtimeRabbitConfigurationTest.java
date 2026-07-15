package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.topology.MineTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验实时事件桥接使用每个服务实例独占的临时队列。
 */
class RealtimeRabbitConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void 队列应由实例独占且服务离线后自动删除() {
        RealtimeProperties properties = properties(tempDir.resolve("password"));
        Queue queue = new RealtimeRabbitConfiguration().realtimeQueue(properties);

        assertThat(queue.getName()).isEqualTo("mine.realtime.server.server-a.q");
        assertThat(queue.isDurable()).isFalse();
        assertThat(queue.isExclusive()).isTrue();
        assertThat(queue.isAutoDelete()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "classic");
    }

    @Test
    void 连接应使用平台虚拟主机和密钥文件中的密码() throws Exception {
        Path password = tempDir.resolve("password");
        Files.writeString(password, " secret-value\n");
        RealtimeProperties properties = properties(password);

        CachingConnectionFactory factory = new RealtimeRabbitConfiguration()
                .realtimeConnectionFactory(properties);

        assertThat(factory.getRabbitConnectionFactory().getVirtualHost())
                .isEqualTo(MineTopology.PLATFORM_VHOST);
        assertThat(factory.getRabbitConnectionFactory().getUsername())
                .isEqualTo("mine-server-realtime");
        assertThat(factory.getRabbitConnectionFactory().getPassword())
                .isEqualTo("secret-value");
        factory.destroy();
    }

    @Test
    void 禁用自动启动时拓扑声明器和监听容器都不得主动连接() throws Exception {
        Path password = tempDir.resolve("password");
        Files.writeString(password, "secret-value");
        RealtimeProperties properties = properties(password);
        RealtimeRabbitConfiguration configuration = new RealtimeRabbitConfiguration();
        CachingConnectionFactory factory = configuration.realtimeConnectionFactory(properties);
        RabbitAdmin admin = configuration.realtimeRabbitAdmin(factory, properties);
        Queue queue = configuration.realtimeQueue(properties);
        RealtimeSessionRegistry registry = new RealtimeSessionRegistry(
                JsonMapper.builder().findAndAddModules().build()
        );

        SimpleMessageListenerContainer container = configuration
                .realtimeMessageListenerContainer(
                        factory,
                        new RealtimeTelemetryConsumer(registry),
                        properties,
                        admin,
                        queue
                );

        assertThat(admin.isAutoStartup()).isFalse();
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.getQueueNames()).containsExactly(queue.getName());
        container.destroy();
        factory.destroy();
    }

    private RealtimeProperties properties(Path passwordFile) {
        return new RealtimeProperties(
                true, null, "server-a", null, null, null, null,
                true, "rabbitmq", 5672, false, 500,
                new RealtimeProperties.Account("mine-server-realtime", passwordFile)
        );
    }
}
