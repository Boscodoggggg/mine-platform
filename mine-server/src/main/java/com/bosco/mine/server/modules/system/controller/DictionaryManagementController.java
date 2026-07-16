package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.system.repository.DictionaryManagementMapper;
import com.bosco.mine.server.modules.system.service.DictionaryManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供系统通用字典类型和数据项的在线管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/system/dictionaries")
public class DictionaryManagementController {

    private final DictionaryManagementService service;

    /**
     * 创建字典管理控制器。
     *
     * @param service 字典管理服务
     */
    public DictionaryManagementController(DictionaryManagementService service) {
        this.service = service;
    }

    /**
     * 查询全部字典类型。
     */
    @GetMapping
    @SaCheckPermission("system:dictionary:view")
    public List<DictionaryManagementMapper.DictTypeRecord> findTypes() {
        return service.findTypes();
    }

    /**
     * 创建字典类型。
     */
    @PostMapping
    @SaCheckPermission("system:dictionary:create")
    public DictionaryManagementMapper.DictTypeRecord createType(@Valid @RequestBody TypeCreateRequest request) {
        return service.createType(new DictionaryManagementService.DictTypeCommand(
                request.code(), request.name(), request.status(), request.description()
        ));
    }

    /**
     * 修改字典类型。
     */
    @PutMapping("/{id}")
    @SaCheckPermission("system:dictionary:update")
    public DictionaryManagementMapper.DictTypeRecord updateType(
            @PathVariable UUID id,
            @Valid @RequestBody TypeUpdateRequest request
    ) {
        return service.updateType(id, request.name(), request.status(), request.description(), request.version());
    }

    /**
     * 删除空字典类型。
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:dictionary:delete")
    public ResponseEntity<Void> deleteType(@PathVariable UUID id) {
        service.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询类型下的数据项。
     */
    @GetMapping("/{typeId}/items")
    @SaCheckPermission("system:dictionary:view")
    public List<DictionaryManagementMapper.DictItemRecord> findItems(@PathVariable UUID typeId) {
        return service.findItems(typeId);
    }

    /**
     * 创建字典数据项。
     */
    @PostMapping("/{typeId}/items")
    @SaCheckPermission("system:dictionary:create")
    public DictionaryManagementMapper.DictItemRecord createItem(
            @PathVariable UUID typeId,
            @Valid @RequestBody ItemCreateRequest request
    ) {
        return service.createItem(typeId, request.toCommand());
    }

    /**
     * 修改字典数据项。
     */
    @PutMapping("/items/{id}")
    @SaCheckPermission("system:dictionary:update")
    public DictionaryManagementMapper.DictItemRecord updateItem(
            @PathVariable UUID id,
            @Valid @RequestBody ItemUpdateRequest request
    ) {
        return service.updateItem(id, request.toCommand());
    }

    /**
     * 删除字典数据项。
     */
    @DeleteMapping("/items/{id}")
    @SaCheckPermission("system:dictionary:delete")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id) {
        service.deleteItem(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 字典类型创建请求。
     */
    public record TypeCreateRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Size(max = 500) String description
    ) {
    }

    /**
     * 字典类型修改请求，稳定编码不允许修改。
     */
    public record TypeUpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Size(max = 500) String description,
            @Min(0) long version
    ) {
    }

    /**
     * 字典数据项创建请求。
     */
    public record ItemCreateRequest(
            @NotBlank @Size(max = 128) String value,
            @NotBlank @Size(max = 128) String labelZh,
            @Size(max = 128) String labelEn,
            @NotBlank @Pattern(regexp = "default|primary|info|success|warning|error") String semanticType,
            @Min(0) int sortOrder,
            boolean defaultItem,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status
    ) {
        /**
         * 转换为应用层创建命令。
         */
        DictionaryManagementService.DictItemCommand toCommand() {
            return new DictionaryManagementService.DictItemCommand(
                    value, labelZh, labelEn, semanticType, sortOrder, defaultItem, status
            );
        }
    }

    /**
     * 字典数据项修改请求，稳定值不允许修改。
     */
    public record ItemUpdateRequest(
            @NotBlank @Size(max = 128) String labelZh,
            @Size(max = 128) String labelEn,
            @NotBlank @Pattern(regexp = "default|primary|info|success|warning|error") String semanticType,
            @Min(0) int sortOrder,
            boolean defaultItem,
            @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
            @Min(0) long version
    ) {
        /**
         * 转换为应用层修改命令。
         */
        DictionaryManagementService.DictItemUpdateCommand toCommand() {
            return new DictionaryManagementService.DictItemUpdateCommand(
                    labelZh, labelEn, semanticType, sortOrder, defaultItem, status, version
            );
        }
    }
}
