package com.bosco.mine.server.modules.command.outbox;

import org.springframework.context.event.EventListener;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 将业务事务提交后的精确事件唤醒转交给独立执行器。
 */
public final class CommandOutboxReadyListener {
    private final CommandOutboxRelay relay;
    private final Executor executor;

    /**
     * 创建提交事件监听器。
     *
     * @param relay Outbox 中继器
     * @param executor 非阻塞发布执行器
     */
    public CommandOutboxReadyListener(CommandOutboxRelay relay, Executor executor) {
        this.relay = Objects.requireNonNull(relay, "Outbox 中继器不能为空");
        this.executor = Objects.requireNonNull(executor, "发布执行器不能为空");
    }

    /**
     * 提交后只投递事件标识，不扫描数据库。
     *
     * @param event 提交唤醒事件
     */
    @EventListener
    public void onReady(OutboxReadyEvent event) {
        executor.execute(() -> relay.publishById(event.eventId()));
    }
}
