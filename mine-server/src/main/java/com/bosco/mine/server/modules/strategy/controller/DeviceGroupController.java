package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.strategy.service.DeviceGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供产品内设备组及当前成员的管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/device-groups")
public class DeviceGroupController {

    private static final ErrorCode MEMBER_DUPLICATED =
            new ErrorCode("STRATEGY-422-003", "成员设备标识不能重复");

    private final DeviceGroupService service;

    /**
     * 创建设备组控制器。
     *
     * @param service 设备组服务
     */
    public DeviceGroupController(DeviceGroupService service) {
        this.service = service;
    }

    /**
     * 分页查询指定产品的设备组。
     *
     * @param productId 产品标识
     * @param keyword   编码或名称关键词
     * @param status    设备组状态
     * @param page      页码
     * @param size      每页数量
     * @return 标准分页结果
     */
    @GetMapping
    @SaCheckPermission("device:group:view")
    public PageResponse<DeviceGroupService.DeviceGroupView> findGroups(
            @RequestParam @NotNull UUID productId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.findGroups(productId, keyword, status, page, size);
    }

    /**
     * 查询单个设备组详情。
     *
     * @param groupId 设备组标识
     * @return 设备组详情
     */
    @GetMapping("/{id}")
    @SaCheckPermission("device:group:view")
    public DeviceGroupService.DeviceGroupView findGroup(@PathVariable("id") UUID groupId) {
        return service.findGroup(groupId);
    }

    /**
     * 查询设备组当前成员。
     *
     * @param groupId 设备组标识
     * @return 当前成员详情
     */
    @GetMapping("/{id}/members")
    @SaCheckPermission("device:group:view")
    public List<DeviceGroupService.DeviceGroupMemberView> findMembers(
            @PathVariable("id") UUID groupId
    ) {
        return service.findMembers(groupId);
    }

    /**
     * 创建设备组。
     *
     * @param request 创建请求
     * @return 新建设备组和资源地址
     */
    @PostMapping
    @SaCheckPermission("device:group:edit")
    public ResponseEntity<DeviceGroupService.DeviceGroupView> createGroup(
            @Valid @RequestBody CreateRequest request
    ) {
        DeviceGroupService.DeviceGroupView created = service.createGroup(
                new DeviceGroupService.CreateGroupCommand(
                        request.productId(), request.groupKey(), request.name(),
                        request.priority(), request.description()
                )
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/device-groups/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 修改设备组可变属性。
     *
     * @param groupId 设备组标识
     * @param request 修改请求
     * @return 修改后的设备组
     */
    @PutMapping("/{id}")
    @SaCheckPermission("device:group:edit")
    public DeviceGroupService.DeviceGroupView updateGroup(
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody UpdateRequest request
    ) {
        return service.updateGroup(groupId, new DeviceGroupService.UpdateGroupCommand(
                request.name(), request.priority(), request.description(), request.version()
        ));
    }

    /**
     * 差异化替换设备组当前成员。
     *
     * @param groupId 设备组标识
     * @param request 成员替换请求
     * @return 成员变化摘要
     */
    @PutMapping("/{id}/members")
    @SaCheckPermission("device:group:edit")
    public DeviceGroupService.MembershipChange replaceMembers(
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody ReplaceMembersRequest request
    ) {
        if (new HashSet<>(request.deviceIds()).size() != request.deviceIds().size()) {
            throw new MineException(MEMBER_DUPLICATED, Map.of("groupId", groupId));
        }
        return service.replaceMembers(groupId, request.deviceIds(), request.version());
    }

    /**
     * 归档设备组并结束当前成员关系。
     *
     * @param groupId 设备组标识
     * @param request 归档请求
     * @return 无内容响应
     */
    @PostMapping("/{id}/archive")
    @SaCheckPermission("device:group:edit")
    public ResponseEntity<Void> archiveGroup(
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody ArchiveRequest request
    ) {
        service.archiveGroup(groupId, request.version());
        return ResponseEntity.noContent().build();
    }

    /**
     * 设备组创建请求。
     *
     * @param productId   所属产品标识
     * @param groupKey    稳定设备组编码
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param description 用途说明
     */
    public record CreateRequest(
            @NotNull UUID productId,
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9-]{2,63}$") String groupKey,
            @NotBlank @Size(max = 128) String name,
            @Min(0) @Max(1000) int priority,
            @Size(max = 500) String description
    ) {
    }

    /**
     * 设备组修改请求。
     *
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param description 用途说明
     * @param version     乐观锁版本号
     */
    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Min(0) @Max(1000) int priority,
            @Size(max = 500) String description,
            @Min(0) long version
    ) {
    }

    /**
     * 设备组成员替换请求。
     *
     * @param deviceIds 目标成员设备标识
     * @param version   乐观锁版本号
     */
    public record ReplaceMembersRequest(
            @NotNull @Size(max = 5000) List<@NotNull UUID> deviceIds,
            @Min(0) long version
    ) {
    }

    /**
     * 设备组归档请求。
     *
     * @param version 乐观锁版本号
     */
    public record ArchiveRequest(@Min(0) long version) {
    }
}
