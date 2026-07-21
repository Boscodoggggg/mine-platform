package com.bosco.mine.server.modules.system.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为现场部署、健康巡检和前端展示提供基础进程状态。
 * 此接口不代表依赖就绪状态，readiness 需要单独检查数据库、消息系统和配置快照。
 */
@RestController
@RequestMapping("/api/v1/system")
public final class SystemStatusController {

    private final String version;

    /**
     * 创建系统状态控制器。
     *
     * @param version 当前平台构建版本
     */
    public SystemStatusController(@Value("${mine.platform.version:unknown}") String version) {
        this.version = version;
    }

    /**
     * 获取当前服务状态和构建版本。
     *
     * @return 系统状态
     */
    @GetMapping("/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse("UP", version);
    }

    /**
     * 系统状态响应。
     *
     * @param status  服务状态
     * @param version 平台版本
     */
    public record SystemStatusResponse(String status, String version) {
    }
}
