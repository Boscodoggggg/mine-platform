package com.bosco.mine.server.modules.mine.operation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在应用启动后持续有界恢复待发布、退避到期和租约过期的运营事件。
 */
public final class OperationOutboxRecovery implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationOutboxRecovery.class);

    private final OperationOutboxRelay relay;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final Duration recoveryInterval;
    private final AtomicBoolean recoveryQueued = new AtomicBoolean();

    /**
     * 创建运营 Outbox 恢复协调器。
     *
     * @param relay 运营 Outbox 中继器
     * @param executor 非阻塞发布执行器
     * @param scheduler 周期恢复调度器
     * @param recoveryInterval 周期恢复间隔
     */
    public OperationOutboxRecovery(
            OperationOutboxRelay relay,
            Executor executor,
            TaskScheduler scheduler,
            Duration recoveryInterval
    ) {
        this.relay = Objects.requireNonNull(relay, "运营 Outbox 中继器不能为空");
        this.executor = Objects.requireNonNull(executor, "运营 Outbox 执行器不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "运营 Outbox 调度器不能为空");
        if (recoveryInterval == null
                || recoveryInterval.isZero() || recoveryInterval.isNegative()) {
            throw new IllegalArgumentException("运营 Outbox 恢复间隔必须大于 0");
        }
        this.recoveryInterval = recoveryInterval;
    }

    /** 启动一次恢复并注册固定延迟的持久恢复扫描。 */
    @Override
    public void run(ApplicationArguments args) {
        submitRecovery();
        scheduler.scheduleWithFixedDelay(this::submitRecovery, recoveryInterval);
    }

    private void submitRecovery() {
        if (!recoveryQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    relay.recoverBounded();
                } catch (RuntimeException exception) {
                    LOGGER.error("运营 Outbox 单轮恢复失败，下轮将继续恢复", exception);
                } finally {
                    recoveryQueued.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            recoveryQueued.set(false);
            LOGGER.warn("运营 Outbox 恢复执行器已关闭，本轮恢复被跳过");
        }
    }
}
