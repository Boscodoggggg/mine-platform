package com.bosco.mine.server.modules.mine.operation.outbox;

import com.bosco.mine.messaging.topology.MineTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验运营 Outbox 使用独立最小权限账号、发布确认和有界生产配置。
 */
class OperationOutboxConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void 发布连接必须读取密钥文件并启用简单确认() throws Exception {
        Path password = tempDir.resolve("password");
        Files.writeString(password, " operation-secret\n");
        OperationOutboxProperties properties = properties(password);

        CachingConnectionFactory factory = new OperationOutboxConfiguration()
                .operationOutboxConnectionFactory(properties);

        assertThat(factory.getRabbitConnectionFactory().getVirtualHost())
                .isEqualTo(MineTopology.PLATFORM_VHOST);
        assertThat(factory.getRabbitConnectionFactory().getUsername())
                .isEqualTo("mine-server-operation-outbox");
        assertThat(factory.getRabbitConnectionFactory().getPassword())
                .isEqualTo("operation-secret");
        assertThat(factory.isSimplePublisherConfirms()).isTrue();
        factory.destroy();
    }

    @Test
    void 默认值必须限制租约重试恢复和周期扫描范围() {
        OperationOutboxProperties properties = new OperationOutboxProperties(
                true, null, null, null, null, null, null, null, null, null, null);

        assertThat(properties.resolvedRabbitHost()).isEqualTo("rabbitmq");
        assertThat(properties.resolvedRabbitPort()).isEqualTo(5672);
        assertThat(properties.resolvedConfirmTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.resolvedLeaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.resolvedMaxAttempts()).isEqualTo(8);
        assertThat(properties.resolvedRecoveryLimit()).isEqualTo(500);
        assertThat(properties.resolvedRecoveryInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    private OperationOutboxProperties properties(Path passwordFile) {
        return new OperationOutboxProperties(
                true, "rabbit-a", 5673,
                Duration.ofSeconds(3), Duration.ofSeconds(30),
                8, 500, Duration.ofSeconds(1), Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                new OperationOutboxProperties.Account(
                        "mine-server-operation-outbox", passwordFile));
    }
}
