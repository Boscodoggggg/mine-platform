package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.device.dto.CredentialRotateRequest;
import com.bosco.mine.server.modules.device.dto.DeviceCredentialResponse;
import com.bosco.mine.server.modules.device.service.DeviceCredentialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 提供设备 MQTT 凭据的一次性签发、轮换和吊销接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class DeviceCredentialController {

    private final DeviceCredentialService credentialService;

    /**
     * 创建设备凭据控制器。
     *
     * @param credentialService 设备凭据服务
     */
    public DeviceCredentialController(DeviceCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * 为设备签发 MQTT 凭据。
     *
     * @param deviceId 设备标识
     * @return 只返回一次明文密钥的签发响应
     */
    @PostMapping("/devices/{id}/credentials")
    @SaCheckPermission("device:credential:create")
    public ResponseEntity<DeviceCredentialResponse> issue(@PathVariable("id") UUID deviceId) {
        DeviceCredentialResponse issued = credentialService.issue(deviceId);
        return ResponseEntity.created(
                URI.create("/api/v1/device-credentials/" + issued.id())
        ).body(issued);
    }

    /**
     * 轮换 MQTT 凭据密钥。
     *
     * @param credentialId 凭据标识
     * @param request      当前版本请求
     * @return 只返回一次新明文密钥的响应
     */
    @PostMapping("/device-credentials/{id}/rotate")
    @SaCheckPermission("device:credential:rotate")
    public DeviceCredentialResponse rotate(
            @PathVariable("id") UUID credentialId,
            @Valid @RequestBody CredentialRotateRequest request
    ) {
        return credentialService.rotate(credentialId, request.version());
    }

    /**
     * 吊销 MQTT 凭据。
     *
     * @param credentialId 凭据标识
     * @param version      当前版本号
     * @return 空响应
     */
    @DeleteMapping("/device-credentials/{id}")
    @SaCheckPermission("device:credential:revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable("id") UUID credentialId,
            @RequestParam @Min(0) long version
    ) {
        credentialService.revoke(credentialId, version);
        return ResponseEntity.noContent().build();
    }
}
