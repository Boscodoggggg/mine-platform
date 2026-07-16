package com.bosco.mine.server.modules.command.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 校验平台 Outbox 只把白名单事件交给对应交换机发布器。
 */
class RoutingOutboxEventPublisherTest {

    @Test
    void 应按稳定事件类型路由命令和配置发布() {
        OutboxEventPublisher commandPublisher = mock(OutboxEventPublisher.class);
        OutboxEventPublisher configurationPublisher = mock(OutboxEventPublisher.class);
        RoutingOutboxEventPublisher publisher = new RoutingOutboxEventPublisher(
                commandPublisher, configurationPublisher);
        OutboxEvent command = event("command.dispatch.requested");
        OutboxEvent configuration = event("config.release.created");

        publisher.publish(command);
        publisher.publish(configuration);

        verify(commandPublisher).publish(command);
        verify(configurationPublisher).publish(configuration);
    }

    @Test
    void 未知事件类型必须拒绝而不能误投递() {
        OutboxEventPublisher commandPublisher = mock(OutboxEventPublisher.class);
        OutboxEventPublisher configurationPublisher = mock(OutboxEventPublisher.class);
        RoutingOutboxEventPublisher publisher = new RoutingOutboxEventPublisher(
                commandPublisher, configurationPublisher);

        assertThatThrownBy(() -> publisher.publish(event("unknown.event")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(commandPublisher, configurationPublisher);
    }

    private OutboxEvent event(String eventType) {
        return new OutboxEvent(
                UUID.fromString("019f6a11-a000-7000-8000-000000000001"),
                UUID.randomUUID(), eventType, 1, "{}", "{}", 1, Instant.now()
        );
    }
}
