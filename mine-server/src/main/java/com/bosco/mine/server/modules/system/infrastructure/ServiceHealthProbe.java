package com.bosco.mine.server.modules.system.infrastructure;

/**
 * 定义一次按需执行、结果可安全展示给系统管理员的健康探测。
 */
@FunctionalInterface
public interface ServiceHealthProbe {

    /**
     * 执行单项健康探测。
     *
     * @return 脱敏后的探测结果
     */
    ProbeResult probe();

    /**
     * 返回探测稳定标识，用于探测抛出异常时生成降级结果。
     *
     * @return 探测标识
     */
    default String key() {
        return "unknown";
    }

    /**
     * 返回探测分组。
     *
     * @return 探测分组
     */
    default String category() {
        return "OTHER";
    }

    /**
     * 单项健康探测结果。
     *
     * @param key           稳定标识
     * @param category      分组
     * @param status        状态
     * @param latencyMillis 探测耗时毫秒数
     * @param summary       脱敏摘要
     */
    record ProbeResult(
            String key,
            String category,
            String status,
            long latencyMillis,
            String summary
    ) {
    }
}
