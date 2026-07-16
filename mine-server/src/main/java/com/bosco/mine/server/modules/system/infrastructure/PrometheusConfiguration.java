package com.bosco.mine.server.modules.system.infrastructure;

import com.bosco.mine.server.modules.system.service.OperationsObservabilityQuery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * 装配系统运行监控使用的 Prometheus 只读客户端。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrometheusProperties.class)
public class PrometheusConfiguration {

    /**
     * 创建使用固定查询模板的历史指标查询端口。
     *
     * @param properties   Prometheus 配置
     * @param objectMapper JSON 映射器
     * @return 历史指标查询端口
     */
    @Bean
    public OperationsObservabilityQuery operationsObservabilityQuery(
            PrometheusProperties properties,
            ObjectMapper objectMapper
    ) {
        return new PrometheusOperationsQuery(properties, objectMapper, Clock.systemUTC());
    }
}
