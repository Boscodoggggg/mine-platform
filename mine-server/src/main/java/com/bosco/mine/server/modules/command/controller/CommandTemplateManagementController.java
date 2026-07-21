package com.bosco.mine.server.modules.command.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.command.template.CommandTemplateRequest;
import com.bosco.mine.server.modules.command.template.CommandTemplateProductResponse;
import com.bosco.mine.server.modules.command.template.CommandTemplateResponse;
import com.bosco.mine.server.modules.command.template.CommandTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供自定义命令模板草稿、编辑和显式发布接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/command-template-definitions")
public class CommandTemplateManagementController {
    private final CommandTemplateService service;

    /**
     * 创建命令模板管理控制器。
     *
     * @param service 命令模板服务
     */
    public CommandTemplateManagementController(CommandTemplateService service) {
        this.service = service;
    }

    /** @return 全部自定义命令模板版本 */
    @GetMapping
    @SaCheckPermission("command:template:view")
    public List<CommandTemplateResponse> list() {
        return service.list();
    }

    /** @return 可绑定命令模板的活动产品 */
    @GetMapping("/products")
    @SaCheckPermission("command:template:view")
    public List<CommandTemplateProductResponse> listProducts() {
        return service.listBindableProducts();
    }

    /**
     * 创建首个模板草稿版本。
     *
     * @param request 模板草稿
     * @return 已创建草稿
     */
    @PostMapping
    @SaCheckPermission("command:template:edit")
    public ResponseEntity<CommandTemplateResponse> create(
            @Valid @RequestBody CommandTemplateRequest request
    ) {
        CommandTemplateResponse created = service.create(request);
        URI location = UriComponentsBuilder
                .fromPath("/api/v1/command-template-definitions/{id}")
                .buildAndExpand(created.versionId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 使用乐观锁修改模板草稿。
     *
     * @param versionId 模板版本标识
     * @param request 模板草稿
     * @return 修改后的草稿
     */
    @PutMapping("/{id}")
    @SaCheckPermission("command:template:edit")
    public CommandTemplateResponse update(
            @PathVariable("id") UUID versionId,
            @Valid @RequestBody CommandTemplateRequest request
    ) {
        return service.update(versionId, request);
    }

    /**
     * 从已发布版本复制下一版可编辑草稿。
     *
     * @param versionId 来源已发布版本标识
     * @return 已创建的下一版草稿
     */
    @PostMapping("/{id}/draft")
    @SaCheckPermission("command:template:edit")
    public ResponseEntity<CommandTemplateResponse> createNextDraft(
            @PathVariable("id") UUID versionId
    ) {
        CommandTemplateResponse created = service.createNextDraft(versionId);
        URI location = UriComponentsBuilder
                .fromPath("/api/v1/command-template-definitions/{id}")
                .buildAndExpand(created.versionId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 显式发布模板草稿，发布后内容不可修改。
     *
     * @param versionId 模板版本标识
     * @param request 发布乐观锁版本
     * @return 已发布版本
     */
    @PostMapping("/{id}/publish")
    @SaCheckPermission("command:template:publish")
    public CommandTemplateResponse publish(
            @PathVariable("id") UUID versionId,
            @Valid @RequestBody PublishRequest request
    ) {
        return service.publish(versionId, request.version());
    }

    /**
     * 模板发布请求。
     *
     * @param version 期望草稿乐观锁版本
     */
    public record PublishRequest(@Min(0) long version) {
    }
}
