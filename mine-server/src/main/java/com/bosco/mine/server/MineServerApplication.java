package com.bosco.mine.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 矿山业务管理服务启动入口。
 */
@SpringBootApplication
public class MineServerApplication {

    /**
     * 启动矿山业务管理服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MineServerApplication.class, args);
    }
}
