package com.bosco.mine.server.modules.alarm;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.alarm.messaging.AlarmEventConsumer;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 校验告警消费者使用独立最小权限账号和手工确认容器。
 */
class AlarmRabbitConfigurationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 应装配平台虚拟主机和手工确认告警队列() throws Exception {
        Path password = Files.writeString(tempDirectory.resolve("password"), "alarm-secret\n");
        AlarmRabbitProperties properties = new AlarmRabbitProperties(
                true, false, "rabbitmq", 5672, 17,
                new AlarmRabbitProperties.Account("mine-server-alarm", password));
        AlarmRabbitConfiguration configuration = new AlarmRabbitConfiguration();
        CachingConnectionFactory factory = configuration.alarmConnectionFactory(properties);
        AlarmEventConsumer consumer = configuration.alarmEventConsumer(
                mock(AlarmEventService.class), mock(AlarmMetrics.class));

        SimpleMessageListenerContainer container = configuration.alarmEventListenerContainer(
                factory, consumer, properties);

        assertThat(factory.getRabbitConnectionFactory().getVirtualHost())
                .isEqualTo(MineTopology.PLATFORM_VHOST);
        assertThat(factory.getRabbitConnectionFactory().getUsername())
                .isEqualTo("mine-server-alarm");
        assertThat(factory.getRabbitConnectionFactory().getPassword()).isEqualTo("alarm-secret");
        assertThat(container.getQueueNames()).containsExactly(MineTopology.SERVER_ALARM_EVENT_QUEUE);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(17);
        assertThat(container.isAutoStartup()).isFalse();
        container.destroy();
        factory.destroy();
    }
}
