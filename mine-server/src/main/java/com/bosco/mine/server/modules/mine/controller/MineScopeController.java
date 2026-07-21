package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.MineArchiveRequest;
import com.bosco.mine.server.modules.mine.dto.MineContextResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationRequest;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.MineScopeGrantRequest;
import com.bosco.mine.server.modules.mine.dto.MineScopeGrantResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteRequest;
import com.bosco.mine.server.modules.mine.dto.MineSiteResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteUpdateRequest;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import com.bosco.mine.server.modules.mine.service.MineScopeQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供组织、矿区、当前矿区上下文和数据范围授权接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine")
public class MineScopeController {

    private final MineScopeCommandService commandService;
    private final MineScopeQueryService queryService;

    /**
     * 创建矿区作用域控制器。
     *
     * @param commandService 矿区作用域写服务
     * @param queryService 矿区作用域查询服务
     */
    public MineScopeController(
            MineScopeCommandService commandService,
            MineScopeQueryService queryService
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** 返回当前用户可选择的活动组织和矿区。 */
    @GetMapping("/context")
    @SaCheckLogin
    public MineContextResponse context() {
        return queryService.context();
    }

    /** 分页查询可见组织。 */
    @GetMapping("/organizations")
    @SaCheckPermission("mine:organization:view")
    public PageResponse<MineOrganizationResponse> findOrganizations(
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findOrganizations(keyword, status, page, size);
    }

    /** 创建组织。 */
    @PostMapping("/organizations")
    @SaCheckPermission("mine:organization:edit")
    public ResponseEntity<MineOrganizationResponse> createOrganization(
            @Valid @RequestBody MineOrganizationRequest request
    ) {
        MineOrganizationResponse created = commandService.createOrganization(
                request.organizationCode(), request.name());
        URI location = UriComponentsBuilder.fromPath("/api/v1/mine/organizations/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** 修改组织。 */
    @PatchMapping("/organizations/{id}")
    @SaCheckPermission("mine:organization:edit")
    public MineOrganizationResponse updateOrganization(
            @PathVariable("id") UUID organizationId,
            @Valid @RequestBody MineOrganizationUpdateRequest request
    ) {
        return commandService.updateOrganization(
                organizationId, request.name(), request.version());
    }

    /** 归档组织。 */
    @PostMapping("/organizations/{id}/archive")
    @SaCheckPermission("mine:organization:edit")
    public ResponseEntity<Void> archiveOrganization(
            @PathVariable("id") UUID organizationId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveOrganization(organizationId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 分页查询组织下的可见矿区。 */
    @GetMapping("/organizations/{organizationId}/sites")
    @SaCheckPermission("mine:site:view")
    public PageResponse<MineSiteResponse> findSites(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findSites(organizationId, keyword, status, page, size);
    }

    /** 创建矿区。 */
    @PostMapping("/organizations/{organizationId}/sites")
    @SaCheckPermission("mine:site:edit")
    public ResponseEntity<MineSiteResponse> createSite(
            @PathVariable UUID organizationId,
            @Valid @RequestBody MineSiteRequest request
    ) {
        MineSiteResponse created = commandService.createSite(
                organizationId, request.siteCode(), request.name(),
                request.timezone(), request.description());
        URI location = UriComponentsBuilder.fromPath("/api/v1/mine/sites/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** 修改矿区。 */
    @PatchMapping("/sites/{id}")
    @SaCheckPermission("mine:site:edit")
    public MineSiteResponse updateSite(
            @PathVariable("id") UUID siteId,
            @Valid @RequestBody MineSiteUpdateRequest request
    ) {
        return commandService.updateSite(
                siteId, request.name(), request.timezone(),
                request.description(), request.version());
    }

    /** 归档矿区。 */
    @PostMapping("/sites/{id}/archive")
    @SaCheckPermission("mine:site:edit")
    public ResponseEntity<Void> archiveSite(
            @PathVariable("id") UUID siteId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveSite(siteId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 查询组织下的数据授权。 */
    @GetMapping("/scope-grants")
    @SaCheckPermission("mine:scope:assign")
    public List<MineScopeGrantResponse> findScopeGrants(@RequestParam UUID organizationId) {
        return queryService.findScopeGrants(organizationId);
    }

    /** 幂等新增数据范围授权。 */
    @PutMapping("/scope-grants")
    @SaCheckPermission("mine:scope:assign")
    public ResponseEntity<Void> grantScope(@Valid @RequestBody MineScopeGrantRequest request) {
        commandService.grantScope(
                request.userId(), request.organizationId(), request.siteId());
        return ResponseEntity.noContent().build();
    }

    /** 幂等撤销数据范围授权。 */
    @DeleteMapping("/scope-grants")
    @SaCheckPermission("mine:scope:assign")
    public ResponseEntity<Void> revokeScope(@Valid @RequestBody MineScopeGrantRequest request) {
        commandService.revokeScope(
                request.userId(), request.organizationId(), request.siteId());
        return ResponseEntity.noContent().build();
    }
}
