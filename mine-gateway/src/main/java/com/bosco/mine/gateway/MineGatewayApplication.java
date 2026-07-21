package com.bosco.mine.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 矿山设备接入服务启动入口。
 */
@SpringBootApplication(
        proxyBeanMethods = false,
        exclude = {RabbitAutoConfiguration.class, RabbitHealthContributorAutoConfiguration.class}
)
public class MineGatewayApplication {

    /**
     * 创建 Spring Boot 应用配置入口。
     */
    public MineGatewayApplication() {
    }

    /**
     * 启动矿山设备接入服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MineGatewayApplication.class, args);
    }
}
