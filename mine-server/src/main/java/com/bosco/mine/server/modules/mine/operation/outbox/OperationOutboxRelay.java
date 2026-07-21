package com.bosco.mine.server.modules.mine.operation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用随机租约领取运营 Outbox，并在发布确认后推进最终状态。
 */
public final class OperationOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationOutboxRelay.class);

    private final Repository repository;
    private final Publisher publisher;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int recoveryLimit;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    /**
     * 创建运营 Outbox 中继器。
     *
     * @param repository 运营 Outbox 仓储
     * @param publisher 带确认的事件发布器
     * @param clock 服务时钟
     * @param leaseDuration 单次发布租约
     * @param maxAttempts 最大尝试次数
     * @param recoveryLimit 单次恢复上限
     * @param retryBaseDelay 重试基础间隔
     * @param retryMaxDelay 重试最大间隔
     */
    public OperationOutboxRelay(
            Repository repository,
            Publisher publisher,
            Clock clock,
            Duration leaseDuration,
            int maxAttempts,
            int recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        this.repository = Objects.requireNonNull(repository, "运营 Outbox 仓储不能为空");
        this.publisher = Objects.requireNonNull(publisher, "运营事件发布器不能为空");
        this.clock = Objects.requireNonNull(clock, "服务时钟不能为空");
        this.leaseDuration = positive(leaseDuration, "运营 Outbox 租约");
        this.retryBaseDelay = positive(retryBaseDelay, "运营 Outbox 重试基础间隔");
        this.retryMaxDelay = positive(retryMaxDelay, "运营 Outbox 重试最大间隔");
        if (maxAttempts < 1 || maxAttempts > 100 || recoveryLimit < 1) {
            throw new IllegalArgumentException("运营 Outbox 尝试次数或恢复上限不合法");
        }
        this.maxAttempts = maxAttempts;
        this.recoveryLimit = recoveryLimit;
    }

    /**
     * 精确领取并发布一个运营事件。
     *
     * @param eventId Outbox 事件标识
     */
    public void publishById(UUID eventId) {
        Objects.requireNonNull(eventId, "运营 Outbox 事件标识不能为空");
        UUID leaseToken = UUID.randomUUID();
        repository.claimById(eventId, leaseToken, leaseDuration)
                .ifPresent(event -> publishClaimed(event, leaseToken));
    }

    /**
     * 有界恢复待发布、失败或租约过期的运营事件。
     *
     * @return 本轮发现事件数
     */
    public int recoverBounded() {
        List<UUID> eventIds = repository.findRecoverableEventIds(recoveryLimit);
        eventIds.forEach(this::publishById);
        return eventIds.size();
    }

    private void publishClaimed(ClaimedEvent event, UUID leaseToken) {
        try {
            publisher.publish(event);
            repository.markPublished(event.eventId(), leaseToken);
        } catch (RuntimeException exception) {
            String error = rootMessage(exception);
            if (event.attemptCount() >= maxAttempts) {
                repository.markDead(event.eventId(), leaseToken, error);
                LOGGER.error("运营 Outbox 事件超过尝试上限并进入死信: eventId={}",
                        event.eventId(), exception);
                return;
            }
            Instant retryAt = clock.instant().plus(retryDelay(event.attemptCount()));
            repository.markFailed(event.eventId(), leaseToken, retryAt, error);
            LOGGER.warn("运营 Outbox 发布失败并进入退避: eventId={}",
                    event.eventId(), exception);
        }
    }

    private Duration retryDelay(int attempts) {
        long exponent = Math.min(Math.max(attempts - 1L, 0L), 20L);
        long multiplier = 1L << exponent;
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(retryBaseDelay.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            delayMillis = retryMaxDelay.toMillis();
        }
        return Duration.ofMillis(Math.min(delayMillis, retryMaxDelay.toMillis()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String value = current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
        return value;
    }

    /** 运营 Outbox 原子状态仓储。 */
    public interface Repository {
        /** 使用随机租约精确领取一个到期事件。 */
        Optional<ClaimedEvent> claimById(UUID eventId, UUID leaseToken, Duration leaseDuration);

        /** 使用持有租约标记发布成功。 */
        void markPublished(UUID eventId, UUID leaseToken);

        /** 使用持有租约记录失败和下次尝试时间。 */
        void markFailed(UUID eventId, UUID leaseToken, Instant retryAt, String error);

        /** 使用持有租约将事件转入死信。 */
        void markDead(UUID eventId, UUID leaseToken, String error);

        /** 有界返回当前可恢复事件标识。 */
        List<UUID> findRecoverableEventIds(int limit);
    }

    /** 等待 RabbitMQ 确认的运营事件发布器。 */
    @FunctionalInterface
    public interface Publisher {
        /** 发布一条已领取运营事件。 */
        void publish(ClaimedEvent event);
    }

    /**
     * 已持有随机租约的运营 Outbox 事件。
     *
     * @param eventId Outbox 事件标识
     * @param organizationId 所属组织标识
     * @param siteId 所属矿区标识
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合标识
     * @param eventType 固定事件类型
     * @param schemaVersion 模式版本
     * @param businessSequence 矿区业务序号
     * @param payloadJson 有界摘要 JSON
     * @param attemptCount 当前领取后的尝试次数
     * @param createdAt Outbox 创建时间
     */
    public record ClaimedEvent(
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int schemaVersion,
            long businessSequence,
            String payloadJson,
            int attemptCount,
            Instant createdAt
    ) {
        /** 校验已领取事件的固定身份和正数序号。 */
        public ClaimedEvent {
            Objects.requireNonNull(eventId, "运营 Outbox 事件标识不能为空");
            Objects.requireNonNull(organizationId, "组织标识不能为空");
            Objects.requireNonNull(siteId, "矿区标识不能为空");
            Objects.requireNonNull(aggregateType, "聚合类型不能为空");
            Objects.requireNonNull(aggregateId, "聚合标识不能为空");
            Objects.requireNonNull(eventType, "事件类型不能为空");
            Objects.requireNonNull(payloadJson, "事件摘要不能为空");
            Objects.requireNonNull(createdAt, "事件创建时间不能为空");
            if (schemaVersion < 1 || businessSequence < 1 || attemptCount < 1) {
                throw new IllegalArgumentException("运营 Outbox 事件版本、序号或次数不合法");
            }
        }
    }
}
