package com.bosco.mine.server.modules.command.outbox;

import org.springframework.context.ApplicationEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * 业务事务提交后发出的 Outbox 精确唤醒事件。
 */
public final class OutboxReadyEvent extends ApplicationEvent {
    private final UUID eventId;

    /**
     * 创建只携带事件标识的唤醒事件。
     *
     * @param source 事件来源
     * @param eventId Outbox 事件标识
     */
    public OutboxReadyEvent(Object source, UUID eventId) {
        super(source);
        this.eventId = Objects.requireNonNull(eventId, "Outbox 事件标识不能为空");
    }

    /**
     * 获取 Outbox 事件标识。
     *
     * @return Outbox 事件标识
     */
    public UUID eventId() {
        return eventId;
    }
}
