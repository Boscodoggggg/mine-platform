package com.bosco.mine.server.modules.mine.operation.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 有界领取并处理运营空间事件，失败时保留租约退避和 DEAD 证据。
 */
@Component
public class OperationAutomationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            OperationAutomationWorker.class);

    private final OperationEventQueue eventQueue;
    private final OperationRuntimeCoordinator coordinator;

    /**
     * 创建运输循环自动化工作器。
     *
     * @param eventQueue 持久空间事件队列
     * @param coordinator 运输运行事务协调器
     */
    public OperationAutomationWorker(
            OperationEventQueue eventQueue,
            OperationRuntimeCoordinator coordinator
    ) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "空间事件队列不能为空");
        this.coordinator = Objects.requireNonNull(coordinator, "运输运行协调器不能为空");
    }

    /**
     * 最多处理指定数量的可领取事件，避免恢复时无限占用执行线程。
     *
     * @param policy 领取、租约和失败退避策略
     * @param stepTimeout 单个运输步骤最大持续时间
     * @param limit 单轮最大事件数
     * @return 本轮已认领事件数
     */
    public int drainBounded(
            OperationEventQueue.ClaimPolicy policy,
            Duration stepTimeout,
            int limit
    ) {
        Objects.requireNonNull(policy, "运营事件领取策略不能为空");
        if (stepTimeout == null || stepTimeout.isZero() || stepTimeout.isNegative()) {
            throw new IllegalArgumentException("运输步骤超时必须大于零");
        }
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("单轮运营事件处理上限不合法");
        }
        int processed = 0;
        while (processed < limit && processOne(policy, stepTimeout)) {
            processed++;
        }
        return processed;
    }

    /**
     * 尝试处理一个事件；业务异常由候选承接，基础设施异常按队列策略退避。
     *
     * @param policy 领取、租约和失败退避策略
     * @param stepTimeout 单个运输步骤最大持续时间
     * @return 是否认领到事件
     */
    public boolean processOne(
            OperationEventQueue.ClaimPolicy policy,
            Duration stepTimeout
    ) {
        Optional<OperationEventQueue.ClaimedEvent> claimed = eventQueue.claimNext(policy);
        if (claimed.isEmpty()) {
            return false;
        }
        OperationEventQueue.ClaimedEvent event = claimed.orElseThrow();
        try {
            coordinator.process(event, stepTimeout);
        } catch (OperationRuntimeCoordinator.LeaseLostException exception) {
            LOGGER.warn("运营空间事件处理租约已丢失，旧执行器停止提交: eventId={}",
                    event.eventId());
        } catch (RuntimeException exception) {
            OperationEventQueue.FailureResult result = eventQueue.fail(event, exception, policy);
            if (result == OperationEventQueue.FailureResult.DEAD) {
                LOGGER.error("运营空间事件超过处理上限并进入 DEAD: eventId={}",
                        event.eventId(), exception);
            } else if (result == OperationEventQueue.FailureResult.RETRY_SCHEDULED) {
                LOGGER.warn("运营空间事件处理失败并已退避: eventId={}",
                        event.eventId(), exception);
            }
        }
        return true;
    }
}
