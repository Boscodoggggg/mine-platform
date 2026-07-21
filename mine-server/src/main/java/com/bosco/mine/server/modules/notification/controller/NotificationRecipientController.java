package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientTargetResponse;
import com.bosco.mine.server.modules.notification.service.NotificationRecipientService;
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
 * 提供通知接收组和加密接收目标管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications/recipient-groups")
public class NotificationRecipientController {

    private final NotificationRecipientService service;

    /**
     * 创建通知接收对象控制器。
     *
     * @param service 通知接收对象管理服务
     */
    public NotificationRecipientController(NotificationRecipientService service) {
        this.service = service;
    }

    /** 返回全部通知接收组。 */
    @GetMapping
    @SaCheckPermission("notification:recipient:view")
    public List<NotificationRecipientGroupResponse> listGroups() {
        return service.listGroups();
    }

    /**
     * 返回指定通知接收组。
     *
     * @param groupId 接收组标识
     * @return 接收组
     */
    @GetMapping("/{groupId}")
    @SaCheckPermission("notification:recipient:view")
    public NotificationRecipientGroupResponse getGroup(
            @PathVariable("groupId") UUID groupId
    ) {
        return service.getGroup(groupId);
    }

    /**
     * 创建通知接收组。
     *
     * @param request 创建请求
     * @return 创建后的接收组
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("notification:recipient:edit")
    public NotificationRecipientGroupResponse createGroup(
            @Valid @RequestBody NotificationRecipientGroupRequest request
    ) {
        return service.createGroup(request);
    }

    /**
     * 使用乐观锁修改通知接收组。
     *
     * @param groupId 接收组标识
     * @param request 修改请求
     * @return 修改后的接收组
     */
    @PutMapping("/{groupId}")
    @SaCheckPermission("notification:recipient:edit")
    public NotificationRecipientGroupResponse updateGroup(
            @PathVariable("groupId") UUID groupId,
            @Valid @RequestBody NotificationRecipientGroupRequest request
    ) {
        return service.updateGroup(groupId, request);
    }

    /**
     * 返回接收组内全部脱敏接收对象。
     *
     * @param groupId 接收组标识
     * @return 接收对象列表
     */
    @GetMapping("/{groupId}/recipients")
    @SaCheckPermission("notification:recipient:view")
    public List<NotificationRecipientResponse> listRecipients(
            @PathVariable("groupId") UUID groupId
    ) {
        return service.listRecipients(groupId);
    }

    /**
     * 在接收组中创建加密接收目标。
     *
     * @param groupId 接收组标识
     * @param request 创建请求
     * @return 创建后的安全响应
     */
    @PostMapping("/{groupId}/recipients")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("notification:recipient:edit")
    public NotificationRecipientResponse createRecipient(
            @PathVariable("groupId") UUID groupId,
            @Valid @RequestBody NotificationRecipientRequest request
    ) {
        return service.createRecipient(groupId, request);
    }

    /**
     * 使用乐观锁修改加密接收目标。
     *
     * @param groupId 接收组标识
     * @param recipientId 接收对象标识
     * @param request 修改请求
     * @return 修改后的安全响应
     */
    @PutMapping("/{groupId}/recipients/{recipientId}")
    @SaCheckPermission("notification:recipient:edit")
    public NotificationRecipientResponse updateRecipient(
            @PathVariable("groupId") UUID groupId,
            @PathVariable("recipientId") UUID recipientId,
            @Valid @RequestBody NotificationRecipientRequest request
    ) {
        return service.updateRecipient(groupId, recipientId, request);
    }

    /**
     * 显式解密并返回接收对象的真实目标。
     *
     * @param groupId 接收组标识
     * @param recipientId 接收对象标识
     * @return 禁止缓存的真实接收目标
     */
    @GetMapping("/{groupId}/recipients/{recipientId}/target")
    @SaCheckPermission("notification:recipient:secret:view")
    public ResponseEntity<NotificationRecipientTargetResponse> target(
            @PathVariable("groupId") UUID groupId,
            @PathVariable("recipientId") UUID recipientId
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.readTarget(groupId, recipientId));
    }
}
