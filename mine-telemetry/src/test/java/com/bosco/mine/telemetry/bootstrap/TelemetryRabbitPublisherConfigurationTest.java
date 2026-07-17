package com.bosco.mine.telemetry.bootstrap;

import com.bosco.mine.telemetry.alarm.outbox.RabbitAlarmOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验告警 Outbox 使用相关确认、发布退回和 mandatory 路由保护。
 */
class TelemetryRabbitPublisherConfigurationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 应启用不可路由检测和相关发布确认() throws Exception {
        Path password = Files.writeString(tempDirectory.resolve("password"), "telemetry-secret\n");
        TelemetryRabbitProperties properties = new TelemetryRabbitProperties(
                false, "rabbitmq", 5672, null, null, null, 3_000L,
                new TelemetryRabbitProperties.Account("mine-telemetry", password), null);
        TelemetryStorageConfiguration configuration = new TelemetryStorageConfiguration();

        CachingConnectionFactory factory = configuration.telemetryConnectionFactory(properties);
        RabbitAlarmOutboxPublisher publisher = configuration.rabbitAlarmOutboxPublisher(
                factory, properties);
        RabbitTemplate template = (RabbitTemplate) ReflectionTestUtils.getField(
                publisher, "rabbitOperations");

        assertThat(factory.isPublisherConfirms()).isTrue();
        assertThat(factory.isSimplePublisherConfirms()).isFalse();
        assertThat(factory.isPublisherReturns()).isTrue();
        assertThat(template).isNotNull();
        assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
        factory.destroy();
    }
}
