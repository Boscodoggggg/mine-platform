package com.bosco.mine.server.modules.command.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 把 Outbox 事件标识在当前业务事务成功提交后发给 relay。
 */
@Component
public final class OutboxCommitNotifier {
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建 Outbox 提交通知器。
     *
     * @param eventPublisher Spring 应用事件发布器
     */
    public OutboxCommitNotifier(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 在当前事务提交成功后发布精确事件标识。
     *
     * @param eventId Outbox 事件标识
     */
    public void notifyAfterCommit(UUID eventId) {
        Objects.requireNonNull(eventId, "Outbox 事件标识不能为空");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Outbox 提交通知必须在活动事务中注册");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(new OutboxReadyEvent(OutboxCommitNotifier.this, eventId));
            }
        });
    }
}
