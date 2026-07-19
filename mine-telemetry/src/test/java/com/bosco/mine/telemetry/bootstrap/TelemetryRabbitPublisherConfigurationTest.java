package com.bosco.mine.telemetry.bootstrap;

import com.bosco.mine.telemetry.alarm.outbox.RabbitAlarmOutboxPublisher;
import com.bosco.mine.telemetry.spatial.ConfirmedSpatialEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
                new TelemetryRabbitProperties.Account("mine-telemetry", password), null, null);
        TelemetryStorageConfiguration configuration = new TelemetryStorageConfiguration();

        CachingConnectionFactory factory = configuration.telemetryConnectionFactory(properties);
        RabbitAlarmOutboxPublisher publisher = configuration.rabbitAlarmOutboxPublisher(
                factory, properties);
        ConfirmedSpatialEventPublisher spatialPublisher =
                configuration.confirmedSpatialEventPublisher(factory, properties);
        RabbitTemplate template = (RabbitTemplate) ReflectionTestUtils.getField(
                publisher, "rabbitOperations");
        RabbitTemplate spatialTemplate = (RabbitTemplate) ReflectionTestUtils.getField(
                spatialPublisher, "rabbitOperations");

        assertThat(factory.isPublisherConfirms()).isTrue();
        assertThat(factory.isSimplePublisherConfirms()).isFalse();
        assertThat(factory.isPublisherReturns()).isTrue();
        assertThat(template).isNotNull();
        assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
        assertThat(spatialTemplate).isNotNull();
        assertThat(spatialTemplate.isMandatoryFor(new Message(new byte[0]))).isTrue();
        factory.destroy();
    }

    @Test
    void 应提供可直接上线的空间事件安全默认参数() {
        TelemetryRabbitProperties properties = new TelemetryRabbitProperties(
                null, null, null, null, null, null, null, null, null, null);

        TelemetryRabbitProperties.Spatial spatial = properties
                .resolvedLocation().resolvedSpatial();

        assertThat(spatial.resolvedEnabled()).isTrue();
        assertThat(spatial.resolvedEvaluationInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(spatial.resolvedEvaluationBatchSize()).isEqualTo(500);
        assertThat(spatial.resolvedOutbox().resolvedInstanceId())
                .isEqualTo("mine-telemetry-spatial");
        assertThat(spatial.resolvedOutbox().resolvedRecoveryLimit()).isEqualTo(2_000);
        assertThat(spatial.resolvedOutbox().resolvedDispatchQueueCapacity()).isEqualTo(5_000);
        assertThat(new TelemetryRabbitProperties.Spatial(false, null, null, null)
                .resolvedEnabled()).isFalse();
    }

    @Test
    void 空间发布租约必须覆盖Rabbit确认超时和安全余量() {
        TelemetryRabbitProperties.Outbox outbox = new TelemetryRabbitProperties.Outbox(
                "spatial-test", Duration.ofSeconds(6), 3, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(10),
                2, 100, Duration.ofSeconds(10));
        TelemetryRabbitProperties.Spatial spatial = new TelemetryRabbitProperties.Spatial(
                true, Duration.ofSeconds(1), 100, outbox);
        TelemetryRabbitProperties.Location location = new TelemetryRabbitProperties.Location(
                null, null, null, null, null, spatial);
        TelemetryRabbitProperties properties = new TelemetryRabbitProperties(
                null, null, null, null, null, null, 5_000L,
                null, null, location);
        TelemetryStorageConfiguration configuration = new TelemetryStorageConfiguration();

        assertThatThrownBy(() -> configuration.spatialEventOutboxRelay(
                mock(DataSource.class), mock(ConfirmedSpatialEventPublisher.class),
                mock(com.bosco.mine.telemetry.spatial.SpatialEventOutboxRelay.RetryScheduler.class),
                mock(ExecutorService.class), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("发布确认超时");
    }
}
