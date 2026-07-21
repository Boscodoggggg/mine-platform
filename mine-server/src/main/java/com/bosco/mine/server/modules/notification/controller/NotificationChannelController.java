package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelSecretsResponse;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.service.NotificationChannelService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供平台内置 Provider 目录和数据库通知渠道配置接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationChannelController {

    private final NotificationChannelService service;

    /**
     * 创建通知渠道控制器。
     *
     * @param service 通知渠道管理服务
     */
    public NotificationChannelController(NotificationChannelService service) {
        this.service = service;
    }

    /** 返回平台内置 Provider 目录。 */
    @GetMapping("/providers")
    @SaCheckPermission("notification:channel:view")
    public List<NotificationProviderDefinition> providers() {
        return service.providers();
    }

    /** 返回全部数据库通知渠道。 */
    @GetMapping("/channels")
    @SaCheckPermission("notification:channel:view")
    public List<NotificationChannelResponse> list() {
        return service.list();
    }

    /**
     * 返回指定数据库通知渠道。
     *
     * @param id 渠道标识
     * @return 通知渠道
     */
    @GetMapping("/channels/{channelId}")
    @SaCheckPermission("notification:channel:view")
    public NotificationChannelResponse get(@PathVariable("channelId") UUID id) {
        return service.get(id);
    }

    /**
     * 显式解密并返回指定渠道的真实敏感凭据。
     *
     * @param id 渠道标识
     * @return 禁止缓存的明文凭据响应
     */
    @GetMapping("/channels/{channelId}/secrets")
    @SaCheckPermission("notification:channel:secret:view")
    public ResponseEntity<NotificationChannelSecretsResponse> secrets(
            @PathVariable("channelId") UUID id
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.readSecrets(id));
    }

    /**
     * 创建数据库通知渠道。
     *
     * @param request 创建请求
     * @return 创建后的通知渠道
     */
    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("notification:channel:edit")
    public NotificationChannelResponse create(
            @Valid @RequestBody NotificationChannelRequest request
    ) {
        return service.create(request);
    }

    /**
     * 使用乐观锁修改数据库通知渠道。
     *
     * @param id 渠道标识
     * @param request 修改请求
     * @return 修改后的通知渠道
     */
    @PutMapping("/channels/{channelId}")
    @SaCheckPermission("notification:channel:edit")
    public NotificationChannelResponse update(
            @PathVariable("channelId") UUID id,
            @Valid @RequestBody NotificationChannelRequest request
    ) {
        return service.update(id, request);
    }
}
