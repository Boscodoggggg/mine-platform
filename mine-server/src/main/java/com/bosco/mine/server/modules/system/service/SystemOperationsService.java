package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.system.infrastructure.ServiceHealthProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按管理员请求执行独立健康探测，不启动定时轮询或后台扫表任务。
 */
@Service
public class SystemOperationsService {

    private static final ErrorCode OPERATION_ITEM_NOT_FOUND =
            new ErrorCode("SYSTEM-404-006", "运行检查项不存在");

    private final List<ServiceHealthProbe> probes;
    private final Duration probeTimeout;
    private final Clock clock;

    /**
     * 创建生产环境系统运行监控服务。
     *
     * @param probes       已注册的健康探测
     * @param probeTimeout 全部单项探测共用的短超时
     */
    @Autowired
    public SystemOperationsService(
            List<ServiceHealthProbe> probes,
            @Value("${mine.operations.probe-timeout:PT2S}") Duration probeTimeout
    ) {
        this(probes, probeTimeout, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的系统运行监控服务。
     *
     * @param probes 已注册的健康探测
     * @param clock  UTC 时钟
     */
    public SystemOperationsService(List<ServiceHealthProbe> probes, Clock clock) {
        this(probes, Duration.ofSeconds(2), clock);
    }

    /**
     * 创建可注入时钟和超时的系统运行监控服务。
     *
     * @param probes       已注册的健康探测
     * @param probeTimeout 全部单项探测共用的短超时
     * @param clock        UTC 时钟
     */
    public SystemOperationsService(List<ServiceHealthProbe> probes, Duration probeTimeout, Clock clock) {
        if (probeTimeout == null || probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("运行探测超时必须大于零");
        }
        this.probes = List.copyOf(probes);
        this.probeTimeout = probeTimeout;
        this.clock = clock;
    }

    /**
     * 执行一次系统运行状态检查。
     *
     * @return 系统运行监控结果
     */
    public SystemOperationsOverview overview() {
        List<FutureTask<OperationItem>> tasks = probes.stream()
                .map(probe -> new FutureTask<>(() -> probeSafely(probe)))
                .toList();
        tasks.forEach(task -> Thread.ofVirtual().name("system-operation-probe").start(task));
        long deadline = System.nanoTime() + probeTimeout.toNanos();
        List<OperationItem> items = new ArrayList<>();
        for (int index = 0; index < probes.size(); index++) {
            items.add(awaitProbe(probes.get(index), tasks.get(index), deadline));
        }
        return new SystemOperationsOverview(overallStatus(items), clock.instant(), List.copyOf(items));
    }

    /**
     * 按稳定标识独立执行一个运行状态检查。
     *
     * @param key 检查项稳定标识
     * @return 单项运行状态
     * @throws MineException 检查项未注册时抛出
     */
    public OperationItem check(String key) {
        ServiceHealthProbe probe = probes.stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new MineException(OPERATION_ITEM_NOT_FOUND, Map.of("key", key)));
        FutureTask<OperationItem> task = new FutureTask<>(() -> probeSafely(probe));
        Thread.ofVirtual().name("system-operation-probe-" + key).start(task);
        return awaitProbe(probe, task, System.nanoTime() + probeTimeout.toNanos());
    }

    private OperationItem awaitProbe(
            ServiceHealthProbe probe,
            FutureTask<OperationItem> task,
            long deadline
    ) {
        long remainingNanos = Math.max(0L, deadline - System.nanoTime());
        try {
            return task.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            return unavailableItem(probe, "checkTimeout");
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return unavailableItem(probe, "checkInterrupted");
        } catch (ExecutionException exception) {
            return unavailableItem(probe, "checkFailed");
        }
    }

    private OperationItem unavailableItem(ServiceHealthProbe probe, String summary) {
        return new OperationItem(probe.key(), probe.category(), "UNKNOWN", 0, summary);
    }

    private OperationItem probeSafely(ServiceHealthProbe probe) {
        try {
            ServiceHealthProbe.ProbeResult result = probe.probe();
            return new OperationItem(
                    result.key(), result.category(), result.status(),
                    result.latencyMillis(), result.summary()
            );
        } catch (RuntimeException exception) {
            return new OperationItem(
                    probe.key(), probe.category(), "UNKNOWN", 0,
                    "checkFailed"
            );
        }
    }

    private String overallStatus(List<OperationItem> items) {
        if (items.isEmpty()) {
            return "UNKNOWN";
        }
        long healthy = items.stream().filter(item -> "UP".equals(item.status())).count();
        if (healthy == items.size()) {
            return "UP";
        }
        if (items.stream().allMatch(item -> "UNKNOWN".equals(item.status()))) {
            return "UNKNOWN";
        }
        if (healthy == 0) {
            return "DOWN";
        }
        return "DEGRADED";
    }

    /**
     * 系统运行监控响应。
     *
     * @param status    总体状态
     * @param checkedAt 检查时间
     * @param items     单项探测
     */
    public record SystemOperationsOverview(String status, Instant checkedAt, List<OperationItem> items) {
    }

    /**
     * 系统运行单项状态。
     *
     * @param key           稳定标识
     * @param category      分组
     * @param status        状态
     * @param latencyMillis 探测耗时毫秒数
     * @param summary       脱敏摘要
     */
    public record OperationItem(
            String key,
            String category,
            String status,
            long latencyMillis,
            String summary
    ) {
    }
}
