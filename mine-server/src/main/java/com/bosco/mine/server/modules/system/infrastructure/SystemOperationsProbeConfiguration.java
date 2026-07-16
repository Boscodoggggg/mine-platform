package com.bosco.mine.server.modules.system.infrastructure;

import com.bosco.mine.server.modules.device.infrastructure.RabbitUserAdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.net.URI;
import java.time.Duration;

/**
 * 注册系统运行监控使用的本地依赖与远程服务按需探测。
 */
@Configuration(proxyBeanMethods = false)
public class SystemOperationsProbeConfiguration {

    /**
     * 注册当前管理服务进程探测。
     *
     * @return 当前进程探测
     */
    @Bean
    @Order(10)
    public ServiceHealthProbe mineServerHealthProbe() {
        return new ServiceHealthProbe() {
            @Override
            public ProbeResult probe() {
                return new ProbeResult("mine-server", "SERVICE", "UP", 0, "serviceUp");
            }

            @Override
            public String key() {
                return "mine-server";
            }

            @Override
            public String category() {
                return "SERVICE";
            }
        };
    }

    /**
     * 注册业务 PostgreSQL 探测。
     *
     * @param registry Actuator 健康贡献者注册表
     * @return 业务库探测
     */
    @Bean
    @Order(20)
    public ServiceHealthProbe postgresHealthProbe(HealthContributorRegistry registry) {
        return new ActuatorContributorHealthProbe("postgres", "STORAGE", "db", registry);
    }

    /**
     * 注册 Redis 探测。
     *
     * @param registry Actuator 健康贡献者注册表
     * @return Redis 探测
     */
    @Bean
    @Order(30)
    public ServiceHealthProbe redisHealthProbe(HealthContributorRegistry registry) {
        return new ActuatorContributorHealthProbe("redis", "STORAGE", "redis", registry);
    }

    /**
     * 注册 TimescaleDB 遥测只读连接探测。
     *
     * @param registry Actuator 健康贡献者注册表
     * @return TimescaleDB 探测
     */
    @Bean
    @Order(40)
    public ServiceHealthProbe timescaleHealthProbe(HealthContributorRegistry registry) {
        return new ActuatorContributorHealthProbe("timescale", "STORAGE", "telemetryQuery", registry);
    }

    /**
     * 注册 RabbitMQ 管理接口探测。
     *
     * @param client RabbitMQ 管理客户端
     * @return RabbitMQ 探测
     */
    @Bean
    @Order(50)
    public ServiceHealthProbe rabbitMqHealthProbe(RabbitUserAdminClient client) {
        return new ServiceHealthProbe() {
            @Override
            public ProbeResult probe() {
                long startedAt = System.nanoTime();
                client.checkHealth();
                long latency = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
                return new ProbeResult("rabbitmq", "MESSAGE", "UP", latency, "rabbitUp");
            }

            @Override
            public String key() {
                return "rabbitmq";
            }

            @Override
            public String category() {
                return "MESSAGE";
            }
        };
    }

    /**
     * 注册设备接入网关探测。
     *
     * @param healthUrl 网关内部健康地址
     * @param timeout   单项探测超时
     * @return 网关探测
     */
    @Bean
    @Order(60)
    public ServiceHealthProbe gatewayHealthProbe(
            @Value("${mine.operations.gateway-health-url:http://mine-gateway:18081/actuator/health}") URI healthUrl,
            @Value("${mine.operations.probe-timeout:PT2S}") Duration timeout
    ) {
        return new HttpServiceHealthProbe("mine-gateway", healthUrl, timeout);
    }

    /**
     * 注册遥测处理服务探测。
     *
     * @param healthUrl 遥测服务内部健康地址
     * @param timeout   单项探测超时
     * @return 遥测服务探测
     */
    @Bean
    @Order(70)
    public ServiceHealthProbe telemetryHealthProbe(
            @Value("${mine.operations.telemetry-health-url:http://mine-telemetry:18082/actuator/health}") URI healthUrl,
            @Value("${mine.operations.probe-timeout:PT2S}") Duration timeout
    ) {
        return new HttpServiceHealthProbe("mine-telemetry", healthUrl, timeout);
    }
}
