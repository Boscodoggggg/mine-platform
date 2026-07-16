package com.bosco.mine.server.modules.system.infrastructure;

import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;

/**
 * 将 Spring Boot 已注册的本地依赖健康项转换为脱敏的管理员展示结果。
 */
public final class ActuatorContributorHealthProbe implements ServiceHealthProbe {

    private final String key;
    private final String category;
    private final String contributorName;
    private final HealthContributorRegistry registry;

    /**
     * 创建本地健康贡献者探测。
     *
     * @param key             页面稳定标识
     * @param category        页面分组
     * @param contributorName Actuator 健康贡献者名称
     * @param registry        健康贡献者注册表
     */
    public ActuatorContributorHealthProbe(
            String key,
            String category,
            String contributorName,
            HealthContributorRegistry registry
    ) {
        this.key = key;
        this.category = category;
        this.contributorName = contributorName;
        this.registry = registry;
    }

    /**
     * 执行本地依赖健康检查。
     *
     * @return 脱敏探测结果
     */
    @Override
    public ProbeResult probe() {
        long startedAt = System.nanoTime();
        HealthContributor contributor = registry.getContributor(contributorName);
        if (contributor == null) {
            return result("UNKNOWN", startedAt, "notEnabled");
        }
        boolean up = isUp(contributor);
        return result(up ? "UP" : "DOWN", startedAt, up ? "connectionUp" : "connectionDown");
    }

    /**
     * 返回页面稳定标识。
     *
     * @return 页面稳定标识
     */
    @Override
    public String key() {
        return key;
    }

    /**
     * 返回页面分组。
     *
     * @return 页面分组
     */
    @Override
    public String category() {
        return category;
    }

    private boolean isUp(HealthContributor contributor) {
        if (contributor instanceof HealthIndicator indicator) {
            return "UP".equals(indicator.health().getStatus().getCode());
        }
        if (contributor instanceof CompositeHealthContributor composite) {
            return composite.stream().allMatch(entry -> isUp(entry.contributor()));
        }
        return false;
    }

    private ProbeResult result(String status, long startedAt, String summary) {
        return new ProbeResult(key, category, status, elapsedMillis(startedAt), summary);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
