package com.bosco.mine.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 独立矿山设备模拟系统启动入口。
 */
@SpringBootApplication
public class SimulatorApplication {

    /**
     * 启动内置 Web 页面和设备模拟运行时。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
