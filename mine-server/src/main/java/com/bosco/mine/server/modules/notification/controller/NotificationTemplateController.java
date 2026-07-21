package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateResponse;
import com.bosco.mine.server.modules.notification.service.NotificationTemplateService;
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
 * 提供安全通知模板和固定变量目录管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/notifications/templates")
public class NotificationTemplateController {

    private final NotificationTemplateService service;

    /**
     * 创建通知模板控制器。
     *
     * @param service 通知模板管理服务
     */
    public NotificationTemplateController(NotificationTemplateService service) {
        this.service = service;
    }

    /** 返回平台支持的固定模板变量目录。 */
    @GetMapping("/variables")
    @SaCheckPermission("notification:template:view")
    public List<String> variables() {
        return service.supportedVariables();
    }

    /** 返回全部通知模板。 */
    @GetMapping
    @SaCheckPermission("notification:template:view")
    public List<NotificationTemplateResponse> list() {
        return service.list();
    }

    /**
     * 返回指定通知模板。
     *
     * @param templateId 模板标识
     * @return 通知模板
     */
    @GetMapping("/{templateId}")
    @SaCheckPermission("notification:template:view")
    public NotificationTemplateResponse get(
            @PathVariable("templateId") UUID templateId
    ) {
        return service.get(templateId);
    }

    /**
     * 创建安全通知模板。
     *
     * @param request 创建请求
     * @return 创建后的模板
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("notification:template:edit")
    public NotificationTemplateResponse create(
            @Valid @RequestBody NotificationTemplateRequest request
    ) {
        return service.create(request);
    }

    /**
     * 使用乐观锁修改通知模板。
     *
     * @param templateId 模板标识
     * @param request 修改请求
     * @return 修改后的模板
     */
    @PutMapping("/{templateId}")
    @SaCheckPermission("notification:template:edit")
    public NotificationTemplateResponse update(
            @PathVariable("templateId") UUID templateId,
            @Valid @RequestBody NotificationTemplateRequest request
    ) {
        return service.update(templateId, request);
    }
}
