package com.bosco.mine.telemetry.alarm.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 以事件标识驱动告警 Outbox 发布，并在 Broker 确认后推进状态。
 */
public final class TelemetryOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryOutboxRelay.class);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final TelemetryOutboxRepository repository;
    private final Publisher publisher;
    private final RetryScheduler retryScheduler;
    private final String instanceId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int recoveryLimit;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;

    /** 创建带有界租约、退避和恢复参数的告警 Outbox 中继器。 */
    public TelemetryOutboxRelay(
            TelemetryOutboxRepository repository,
            Publisher publisher,
            RetryScheduler retryScheduler,
            String instanceId,
            Duration leaseDuration,
            int maxAttempts,
            int recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "告警 Outbox 仓储不能为空");
        this.publisher = Objects.requireNonNull(publisher, "告警事件发布器不能为空");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "告警重试调度器不能为空");
        this.instanceId = requireText(instanceId, "告警 Outbox 实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "告警 Outbox 租约");
        this.retryBaseDelay = requirePositive(retryBaseDelay, "告警重试基础间隔");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "告警重试最大间隔");
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("告警重试基础间隔不能大于最大间隔");
        }
        if (maxAttempts < 1 || recoveryLimit < 1 || recoveryLimit > 10_000) {
            throw new IllegalArgumentException("告警尝试次数或恢复上限不合法");
        }
        this.maxAttempts = maxAttempts;
        this.recoveryLimit = recoveryLimit;
        this.clock = Objects.requireNonNull(clock, "告警 Outbox 时钟不能为空");
    }

    /**
     * 精确发布一个已知事件；无法认领表示已被其他实例处理。
     *
     * @param eventId 事件标识
     */
    public void publishById(UUID eventId) {
        repository.claimById(eventId, instanceId, leaseDuration).ifPresent(this::publishClaimed);
    }

    /**
     * 启动或 RabbitMQ 重连时执行一次有界恢复。
     *
     * @return 本次发现的可恢复事件数量
     */
    public int recoverBounded() {
        List<UUID> eventIds = repository.findRecoverableEventIds(recoveryLimit);
        eventIds.forEach(this::publishById);
        return eventIds.size();
    }

    private void publishClaimed(TelemetryOutboxRepository.ClaimedEvent event) {
        try {
            publisher.publish(event);
            repository.markPublished(event.eventId(), instanceId);
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            if (event.attempts() >= maxAttempts) {
                repository.markDead(event.eventId(), instanceId, error);
                LOGGER.error("告警 Outbox 事件超过尝试上限并进入死信: eventId={}, error={}",
                        event.eventId(), error);
                return;
            }
            Instant retryAt = clock.instant().plus(retryDelay(event.attempts()));
            repository.markFailed(event.eventId(), instanceId, retryAt, error);
            retryScheduler.schedule(event.eventId(), retryAt);
            LOGGER.warn("告警 Outbox 发布失败并已安排精确重试: eventId={}, error={}",
                    event.eventId(), error);
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

    private String safeError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ");
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "长度不合法");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }

    /** 等待 RabbitMQ publisher confirm 的告警事件发布器。 */
    @FunctionalInterface
    public interface Publisher {
        /** 发布事件；未确认或契约非法时抛出异常。 */
        void publish(TelemetryOutboxRepository.ClaimedEvent event);
    }

    /** 仅调度一个已知事件标识的进程内重试器。 */
    @FunctionalInterface
    public interface RetryScheduler {
        /** 在指定时间重新唤醒同一事件。 */
        void schedule(UUID eventId, Instant retryAt);
    }
}
