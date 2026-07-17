package com.bosco.mine.server.modules.command.outbox;

import java.util.Objects;

/**
 * 按稳定事件类型把平台 Outbox 事件路由到最小权限交换机发布器。
 */
public final class RoutingOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxEventPublisher commandPublisher;
    private final OutboxEventPublisher configurationPublisher;
    private final OutboxEventPublisher strategyPublisher;

    /**
     * 创建白名单 Outbox 路由器。
     *
     * @param commandPublisher 命令事件发布器
     * @param configurationPublisher 配置发布事件发布器
     * @param strategyPublisher 策略运行事件发布器
     */
    public RoutingOutboxEventPublisher(
            OutboxEventPublisher commandPublisher,
            OutboxEventPublisher configurationPublisher,
            OutboxEventPublisher strategyPublisher
    ) {
        this.commandPublisher = Objects.requireNonNull(commandPublisher, "命令发布器不能为空");
        this.configurationPublisher = Objects.requireNonNull(
                configurationPublisher, "配置发布器不能为空");
        this.strategyPublisher = Objects.requireNonNull(strategyPublisher, "策略发布器不能为空");
    }

    /**
     * 只投递明确允许的命令和配置发布事件。
     *
     * @param event 已认领事件
     */
    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 事件不能为空");
        switch (event.eventType()) {
            case "command.dispatch.requested" -> commandPublisher.publish(event);
            case "config.release.created" -> configurationPublisher.publish(event);
            case "strategy.runtime.changed" -> strategyPublisher.publish(event);
            default -> throw new IllegalArgumentException("不支持的 Outbox 事件类型: " + event.eventType());
        }
    }
}
