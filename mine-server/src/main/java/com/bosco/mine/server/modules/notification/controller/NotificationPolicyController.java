package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyActionRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyPublishRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyResponse;
import com.bosco.mine.server.modules.notification.service.NotificationPolicyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * 提供通知升级策略草稿、发布和归档管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications/policies")
public class NotificationPolicyController {

    private final NotificationPolicyService service;

    /**
     * 创建通知升级策略控制器。
     *
     * @param service 通知升级策略服务
     */
    public NotificationPolicyController(NotificationPolicyService service) {
        this.service = service;
    }

    /** 返回全部通知升级策略。 */
    @GetMapping
    @SaCheckPermission("notification:policy:view")
    public List<NotificationPolicyResponse> list() {
        return service.list();
    }

    /** 返回指定通知升级策略。 */
    @GetMapping("/{policyId}")
    @SaCheckPermission("notification:policy:view")
    public NotificationPolicyResponse get(@PathVariable("policyId") UUID policyId) {
        return service.get(policyId);
    }

    /** 创建策略根和第一版草稿。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("notification:policy:edit")
    public NotificationPolicyResponse create(
            @Valid @RequestBody NotificationPolicyRequest request
    ) {
        return service.create(request);
    }

    /** 整体修改当前草稿。 */
    @PutMapping("/{policyId}/draft")
    @SaCheckPermission("notification:policy:edit")
    public NotificationPolicyResponse updateDraft(
            @PathVariable("policyId") UUID policyId,
            @Valid @RequestBody NotificationPolicyRequest request
    ) {
        return service.updateDraft(policyId, request);
    }

    /** 从当前已发布快照派生新草稿。 */
    @PostMapping("/{policyId}/drafts")
    @SaCheckPermission("notification:policy:edit")
    public NotificationPolicyResponse createDraft(
            @PathVariable("policyId") UUID policyId,
            @Valid @RequestBody NotificationPolicyActionRequest request
    ) {
        return service.createDraft(policyId, request);
    }

    /** 发布指定草稿版本。 */
    @PostMapping("/{policyId}/publish")
    @SaCheckPermission("notification:policy:publish")
    public NotificationPolicyResponse publish(
            @PathVariable("policyId") UUID policyId,
            @Valid @RequestBody NotificationPolicyPublishRequest request
    ) {
        return service.publish(policyId, request);
    }

    /** 归档策略并停止参与新告警匹配。 */
    @PostMapping("/{policyId}/archive")
    @SaCheckPermission("notification:policy:edit")
    public NotificationPolicyResponse archive(
            @PathVariable("policyId") UUID policyId,
            @Valid @RequestBody NotificationPolicyActionRequest request
    ) {
        return service.archive(policyId, request);
    }
}
