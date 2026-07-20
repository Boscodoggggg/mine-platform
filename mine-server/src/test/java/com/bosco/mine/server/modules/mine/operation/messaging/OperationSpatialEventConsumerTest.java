package com.bosco.mine.server.modules.mine.operation.messaging;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationEventQueue;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationRuntimeConfiguration;
import com.rabbitmq.client.Channel;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * 校验运营空间事件消费者的契约白名单、手工确认和独立持久队列配置。
 */
class OperationSpatialEventConsumerTest {

    private static final UUID EVENT_ID = uuidV7(1);
    private static final UUID ORGANIZATION_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000101");
    private static final UUID SITE_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000102");
    private static final UUID ZONE_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000103");
    private static final UUID ZONE_VERSION_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000104");
    private static final UUID TAG_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000105");
    private static final UUID VEHICLE_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000106");
    private static final UUID ANCHOR_ID = UUID.fromString(
            "019f64e5-a000-7000-8000-000000000107");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-20T01:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void 事务入队成功后才应确认消息() throws Exception {
        OperationEventQueue queue = mock(OperationEventQueue.class);
        when(queue.enqueue(any(RealtimeSpatialEvent.class)))
                .thenReturn(OperationEventQueue.EnqueueResult.ACCEPTED);
        Channel channel = mock(Channel.class);

        new OperationSpatialEventConsumer(queue).onMessage(message(71L, envelope(1)), channel);

        verify(queue).enqueue(any(RealtimeSpatialEvent.class));
        verify(channel).basicAck(71L, false);
        verify(channel, never()).basicNack(71L, false, true);
    }

    @Test
    void 数据库暂时失败应交给容器退避且不能自行热重投() throws Exception {
        OperationEventQueue queue = mock(OperationEventQueue.class);
        doThrow(new IllegalStateException("数据库暂时不可用"))
                .when(queue).enqueue(any(RealtimeSpatialEvent.class));
        Channel channel = mock(Channel.class);

        assertThatThrownBy(() -> new OperationSpatialEventConsumer(queue)
                .onMessage(message(72L, envelope(1)), channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("数据库暂时不可用");

        verify(channel, never()).basicNack(72L, false, true);
        verify(channel, never()).basicAck(72L, false);
    }

    @Test
    void 数据库持续失败应指数退避后重入队而不是提前死信() throws Throwable {
        OperationRuntimeConfiguration.RuntimeProperties properties = properties(
                tempDirectory.resolve("missing-password"), 3,
                Duration.ofMillis(1), Duration.ofMillis(2));
        MethodInterceptor advice = new OperationRuntimeConfiguration()
                .operationIngressRetryAdvice(properties);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{
                mock(Channel.class), message(75L, envelope(1))
        });
        when(invocation.proceed()).thenThrow(new IllegalStateException("数据库持续不可用"));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(ImmediateRequeueAmqpException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(invocation, times(3)).proceed();
    }

    @Test
    void 不受支持的结构版本和身份冲突必须永久拒绝() throws Exception {
        OperationEventQueue queue = mock(OperationEventQueue.class);
        Channel channel = mock(Channel.class);
        OperationSpatialEventConsumer consumer = new OperationSpatialEventConsumer(queue);

        consumer.onMessage(message(73L, envelope(2)), channel);
        doThrow(new OperationEventQueue.IdentityConflictException("空间身份冲突"))
                .when(queue).enqueue(any(RealtimeSpatialEvent.class));
        consumer.onMessage(message(74L, envelope(1)), channel);

        verify(channel).basicReject(73L, false);
        verify(channel).basicReject(74L, false);
        verify(channel, never()).basicNack(73L, false, true);
        verify(channel, never()).basicNack(74L, false, true);
    }

    @Test
    void 独立消费者必须使用平台虚拟主机和手工确认持久队列() throws Exception {
        Path password = Files.writeString(
                tempDirectory.resolve("operation-password"), "operation-secret\n");
        OperationRuntimeConfiguration.RuntimeProperties properties = properties(
                password, 3, Duration.ofSeconds(1), Duration.ofSeconds(4));
        OperationRuntimeConfiguration configuration = new OperationRuntimeConfiguration();
        CachingConnectionFactory factory = configuration.operationConnectionFactory(properties);
        OperationSpatialEventConsumer consumer = configuration.operationSpatialEventConsumer(
                mock(OperationEventQueue.class));
        MethodInterceptor retryAdvice = configuration.operationIngressRetryAdvice(properties);

        SimpleMessageListenerContainer container =
                configuration.operationEventListenerContainer(
                        factory, consumer, retryAdvice, properties);

        assertThat(factory.getRabbitConnectionFactory().getVirtualHost())
                .isEqualTo(MineTopology.PLATFORM_VHOST);
        assertThat(factory.getRabbitConnectionFactory().getUsername())
                .isEqualTo("mine-server-operation");
        assertThat(factory.getRabbitConnectionFactory().getPassword())
                .isEqualTo("operation-secret");
        assertThat(container.getQueueNames())
                .containsExactly(MineTopology.SERVER_OPERATION_SPATIAL_QUEUE);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(23);
        assertThat(ReflectionTestUtils.getField(container, "adviceChain"))
                .as("数据库异常必须经过有界退避 advice")
                .isNotNull();
        assertThat(container.isAutoStartup()).isFalse();
        container.destroy();
        factory.destroy();
    }

    private OperationRuntimeConfiguration.RuntimeProperties properties(
            Path password,
            int ingressRetryAttempts,
            Duration ingressRetryBaseDelay,
            Duration ingressRetryMaxDelay
    ) {
        return new OperationRuntimeConfiguration.RuntimeProperties(
                true, false, "rabbitmq", 5672, 23,
                ingressRetryAttempts, ingressRetryBaseDelay, ingressRetryMaxDelay,
                Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 8,
                Duration.ofSeconds(1), Duration.ofMinutes(1),
                new OperationRuntimeConfiguration.Account(
                        "mine-server-operation", password));
    }

    private byte[] envelope(int schemaVersion) throws Exception {
        RealtimeSpatialEvent payload = event();
        MessageEnvelope<RealtimeSpatialEvent> envelope = new MessageEnvelope<>(
                EVENT_ID, MessageType.of("realtime.spatial.event.created"), schemaVersion,
                OCCURRED_AT, OCCURRED_AT.plusSeconds(1), "mine-telemetry",
                ANCHOR_ID.toString(), ZONE_ID.toString(), SITE_ID.toString(),
                "mine-local", payload);
        return JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(envelope);
    }

    private Message message(long deliveryTag, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey(
                MineTopology.REALTIME_SPATIAL_ROUTING_PREFIX + SITE_ID);
        properties.setHeader("eventType", "realtime.spatial.event.created");
        properties.setHeader("schemaVersion", 1);
        properties.setHeader("siteId", SITE_ID.toString());
        properties.setHeader("zoneType", "GEOFENCE");
        return new Message(body, properties);
    }

    private RealtimeSpatialEvent event() {
        return new RealtimeSpatialEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                RealtimeSpatialEvent.ZoneType.GEOFENCE, ZONE_ID, ZONE_VERSION_ID,
                TAG_ID, VEHICLE_ID, null, RealtimeSpatialEvent.EventType.ENTER,
                ANCHOR_ID, RealtimeSpatialEvent.StableState.OUTSIDE,
                RealtimeSpatialEvent.StableState.INSIDE,
                2, 7, 3, OCCURRED_AT,
                new RealtimeSpatialEvent.Evidence(
                        100.5, 200.5, null, "INSIDE", "GOOD", null,
                        "sha256:" + "a".repeat(64)));
    }

    private static UUID uuidV7(int suffix) {
        return UUID.fromString("019f64e5-a000-7000-8000-" + "%012x".formatted(suffix));
    }
}
