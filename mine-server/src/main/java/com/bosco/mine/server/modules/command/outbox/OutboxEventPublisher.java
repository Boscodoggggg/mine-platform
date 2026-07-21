package com.bosco.mine.server.modules.command.outbox;

/**
 * 将已认领 Outbox 事件可靠发布到内部消息总线。
 */
@FunctionalInterface
public interface OutboxEventPublisher {

    /**
     * 发布事件并等待消息代理确认；未确认时必须抛出异常。
     *
     * @param event 已认领事件
     */
    void publish(OutboxEvent event);
}
