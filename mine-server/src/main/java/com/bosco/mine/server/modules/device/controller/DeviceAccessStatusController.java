package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.device.dto.DeviceAccessStatusResponse;
import com.bosco.mine.server.modules.device.dto.DeviceAccessTestResponse;
import com.bosco.mine.server.modules.device.service.DeviceAccessStatusService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供设备持续接入状态和四层手动检查接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceAccessStatusController {

    private final DeviceAccessStatusService service;

    /**
     * 创建设备接入状态控制器。
     *
     * @param service 设备接入状态服务
     */
    public DeviceAccessStatusController(DeviceAccessStatusService service) {
        this.service = service;
    }

    /**
     * 批量查询设备持续接入状态。
     *
     * @param deviceIds 设备标识集合
     * @return 接入状态列表
     */
    @GetMapping("/access-status")
    @SaCheckPermission("device:device:view")
    public List<DeviceAccessStatusResponse> find(
            @RequestParam @NotEmpty @Size(max = 100) List<UUID> deviceIds
    ) {
        return service.find(deviceIds);
    }

    /**
     * 手动执行设备四层接入检查。
     *
     * @param deviceId 设备标识
     * @return 四层检查结果
     */
    @PostMapping("/{id}/access-status/test")
    @SaCheckPermission("device:connection:test")
    public DeviceAccessTestResponse test(@PathVariable("id") UUID deviceId) {
        return service.test(deviceId);
    }
}
