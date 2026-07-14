package com.bosco.mine.telemetry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 矿山遥测处理服务启动入口。
 */
@SpringBootApplication(proxyBeanMethods = false)
public class MineTelemetryApplication {

    /**
     * 创建 Spring Boot 应用配置入口。
     */
    public MineTelemetryApplication() {
    }

    /**
     * 启动矿山遥测处理服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MineTelemetryApplication.class, args);
    }
}
