package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileRequest;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.service.DeviceConnectionService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供设备接入配置和分层连通性诊断接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/devices/{id}/connection-profile")
public class DeviceConnectionController {

    private final DeviceConnectionService service;

    /**
     * 创建设备接入控制器。
     *
     * @param service 设备接入服务
     */
    public DeviceConnectionController(DeviceConnectionService service) {
        this.service = service;
    }

    /**
     * 查询设备接入配置与最近诊断结果。
     *
     * @param deviceId 设备标识
     * @return 不含凭据的接入配置
     */
    @GetMapping
    @SaCheckPermission("device:connection:view")
    public DeviceConnectionProfileResponse get(@PathVariable("id") UUID deviceId) {
        return service.get(deviceId);
    }

    /**
     * 创建或修改设备接入配置。
     *
     * @param deviceId 设备标识
     * @param request  保存请求
     * @return 保存后的接入配置
     */
    @PutMapping
    @SaCheckPermission("device:connection:update")
    public DeviceConnectionProfileResponse save(
            @PathVariable("id") UUID deviceId,
            @Valid @RequestBody DeviceConnectionProfileRequest request
    ) {
        return service.save(deviceId, request);
    }

    /**
     * 执行配置、DNS、TCP、TLS 和设备确认分层诊断。
     *
     * @param deviceId 设备标识
     * @return 本次诊断结果
     */
    @PostMapping("/test")
    @SaCheckPermission("device:connection:test")
    public DeviceConnectionTestResponse test(@PathVariable("id") UUID deviceId) {
        return service.test(deviceId);
    }
}
