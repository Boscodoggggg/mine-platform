package com.bosco.mine.server.modules.command.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 以事件标识驱动平台 Outbox 发布，并在 RabbitMQ 确认后推进状态。
 */
public final class CommandOutboxRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandOutboxRelay.class);

    private final OutboxRelayRepository repository;
    private final OutboxEventPublisher publisher;
    private final KnownEventRetryScheduler retryScheduler;
    private final String instanceId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int recoveryLimit;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    /**
     * 创建命令 Outbox 中继器。
     */
    public CommandOutboxRelay(
            OutboxRelayRepository repository,
            OutboxEventPublisher publisher,
            KnownEventRetryScheduler retryScheduler,
            String instanceId,
            Duration leaseDuration,
            int maxAttempts,
            int recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        this.repository = Objects.requireNonNull(repository, "Outbox 仓储不能为空");
        this.publisher = Objects.requireNonNull(publisher, "事件发布器不能为空");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "重试调度器不能为空");
        this.instanceId = requireText(instanceId, "实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "认领租约");
        this.retryBaseDelay = requirePositive(retryBaseDelay, "重试基础间隔");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "重试最大间隔");
        if (maxAttempts < 1 || recoveryLimit < 1) {
            throw new IllegalArgumentException("尝试次数和恢复上限必须大于 0");
        }
        this.maxAttempts = maxAttempts;
        this.recoveryLimit = recoveryLimit;
    }

    /**
     * 精确发布一个事件；无法认领表示事件已被其他实例处理。
     *
     * @param eventId 事件标识
     */
    public void publishById(UUID eventId) {
        repository.claimById(eventId, instanceId, leaseDuration).ifPresent(this::publishClaimed);
    }

    /**
     * 启动或 RabbitMQ 重连时执行一次有界恢复，不注册周期任务。
     *
     * @return 本次发现的事件数量
     */
    public int recoverBounded() {
        List<UUID> eventIds = repository.findRecoverableEventIds(recoveryLimit);
        eventIds.forEach(this::publishById);
        return eventIds.size();
    }

    private void publishClaimed(OutboxEvent event) {
        try {
            publisher.publish(event);
            repository.markPublished(event.eventId(), instanceId);
        } catch (RuntimeException exception) {
            String error = rootMessage(exception);
            if (event.attempts() >= maxAttempts) {
                repository.markDead(event.eventId(), instanceId, error);
                LOGGER.error("平台 Outbox 事件超过尝试上限并进入死信: eventId={}", event.eventId(), exception);
                return;
            }
            Instant retryAt = Instant.now().plus(retryDelay(event.attempts()));
            repository.markFailed(event.eventId(), instanceId, retryAt, error);
            retryScheduler.schedule(event.eventId(), retryAt);
            LOGGER.warn("平台 Outbox 发布失败，已按事件标识安排重试: eventId={}", event.eventId(), exception);
        }
    }

    private Duration retryDelay(int attempts) {
        long exponent = Math.min(attempts - 1L, 20L);
        long multiplier = 1L << exponent;
        long delayedMillis;
        try {
            delayedMillis = Math.multiplyExact(retryBaseDelay.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            delayedMillis = retryMaxDelay.toMillis();
        }
        return Duration.ofMillis(Math.min(delayedMillis, retryMaxDelay.toMillis()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }
}
