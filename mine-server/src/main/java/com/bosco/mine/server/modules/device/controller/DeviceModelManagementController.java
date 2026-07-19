package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.device.repository.DeviceModelManagementMapper;
import com.bosco.mine.server.modules.device.service.DeviceModelManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 提供设备物模型和厂商协议字段映射的在线配置与发布接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/device-models")
public class DeviceModelManagementController {

    private final DeviceModelManagementService service;

    /**
     * 创建设备模型管理控制器。
     */
    public DeviceModelManagementController(DeviceModelManagementService service) {
        this.service = service;
    }

    /** 查询产品全部配置版本。 */
    @GetMapping("/products/{productId}/configuration")
    @SaCheckPermission("device:model:view")
    public DeviceModelManagementService.ProductConfiguration findConfiguration(@PathVariable UUID productId) {
        return service.findConfiguration(productId);
    }

    /** 新建物模型草稿。 */
    @PostMapping("/products/{productId}/model-drafts")
    @SaCheckPermission("device:model:edit")
    public DeviceModelManagementMapper.ModelVersionRecord createModelDraft(
            @PathVariable UUID productId,
            @Valid @RequestBody DescriptionRequest request
    ) {
        return service.createModelDraft(productId, request.description());
    }

    /** 原子替换物模型草稿字段。 */
    @PutMapping("/model-versions/{versionId}/fields")
    @SaCheckPermission("device:model:edit")
    public List<DeviceModelManagementMapper.ModelFieldRecord> replaceModelFields(
            @PathVariable UUID versionId,
            @Valid @RequestBody ModelFieldsRequest request
    ) {
        return service.replaceModelFields(versionId, request.fields().stream().map(ModelFieldRequest::toCommand).toList());
    }

    /** 发布物模型草稿。 */
    @PostMapping("/model-versions/{versionId}/publish")
    @SaCheckPermission("device:model:publish")
    public ResponseEntity<Void> publishModel(
            @PathVariable UUID versionId,
            @Valid @RequestBody PublishRequest request
    ) {
        service.publishModel(versionId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 新建协议映射草稿。 */
    @PostMapping("/products/{productId}/protocol-drafts")
    @SaCheckPermission("device:model:edit")
    public DeviceModelManagementMapper.ProtocolVersionRecord createProtocolDraft(
            @PathVariable UUID productId,
            @Valid @RequestBody ProtocolDraftRequest request
    ) {
        return service.createProtocolDraft(productId, request.toCommand());
    }

    /** 原子替换协议草稿字段映射。 */
    @PutMapping("/protocol-versions/{versionId}/fields")
    @SaCheckPermission("device:model:edit")
    public List<DeviceModelManagementMapper.ProtocolFieldRecord> replaceProtocolFields(
            @PathVariable UUID versionId,
            @Valid @RequestBody ProtocolFieldsRequest request
    ) {
        return service.replaceProtocolFields(
                versionId,
                request.fields().stream().map(ProtocolFieldRequest::toCommand).toList()
        );
    }

    /** 发布协议映射草稿。 */
    @PostMapping("/protocol-versions/{versionId}/publish")
    @SaCheckPermission("device:model:publish")
    public ResponseEntity<Void> publishProtocol(
            @PathVariable UUID versionId,
            @Valid @RequestBody PublishRequest request
    ) {
        service.publishProtocol(versionId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 草稿说明请求。 */
    public record DescriptionRequest(@Size(max = 500) String description) {
    }

    /** 发布请求。 */
    public record PublishRequest(@Min(0) long version) {
    }

    /** 物模型字段集合请求。 */
    public record ModelFieldsRequest(@NotEmpty @Size(max = 500) List<@Valid ModelFieldRequest> fields) {
    }

    /** 物模型字段请求。 */
    public record ModelFieldRequest(
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,127}") String identifier,
            @NotBlank @Size(max = 128) String nameZh,
            @Size(max = 128) String nameEn,
            @NotBlank @Pattern(regexp = "INTEGER|DECIMAL|BOOLEAN|STRING|ENUM|DATETIME|GEO_POINT") String dataType,
            @Size(max = 32) String unit,
            @NotBlank @Pattern(regexp = "READ|WRITE|READ_WRITE") String accessMode,
            boolean requiredField,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            @Min(0) Integer decimalPlaces,
            @Min(0) int sortOrder,
            @Size(max = 500) String description
    ) {
        /** 转换为应用层命令。 */
        DeviceModelManagementService.ModelFieldCommand toCommand() {
            return new DeviceModelManagementService.ModelFieldCommand(
                    identifier, nameZh, nameEn, dataType, unit, accessMode, requiredField,
                    minimumValue, maximumValue, decimalPlaces, sortOrder, description
            );
        }
    }

    /** 协议映射草稿请求。 */
    public record ProtocolDraftRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{2,63}") String profileKey,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "MQTT_JSON|MQTT_BINARY|TCP_BINARY") String protocolType,
            @NotBlank @Pattern(regexp = "TELEMETRY|STATE|EVENT|COMMAND_REPLY") String messageType,
            @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String pluginKey,
            @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String pluginVersion,
            @Pattern(regexp = "TRUSTED_IDENTITY|LEGACY_LISTENER") String routingMode,
            @Size(max = 255) String topicOrFrame,
            @Size(max = 500) String description
    ) {
        /** 转换为应用层命令。 */
        DeviceModelManagementService.ProtocolDraftCommand toCommand() {
            return new DeviceModelManagementService.ProtocolDraftCommand(
                    profileKey, name, protocolType, messageType,
                    pluginKey, pluginVersion, routingMode, topicOrFrame, description
            );
        }
    }

    /** 协议字段映射集合请求。 */
    public record ProtocolFieldsRequest(@NotEmpty @Size(max = 1000) List<@Valid ProtocolFieldRequest> fields) {
    }

    /** 协议字段映射请求。 */
    public record ProtocolFieldRequest(
            @NotBlank @Size(max = 255) String sourcePath,
            @NotBlank @Pattern(regexp = "INTEGER|DECIMAL|BOOLEAN|STRING|BINARY|DATETIME") String sourceDataType,
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,127}") String targetIdentifier,
            @NotBlank @Pattern(regexp = "NONE|SCALE|OFFSET|SCALE_OFFSET|ENUM|EXPRESSION") String transformType,
            BigDecimal scaleFactor,
            BigDecimal offsetValue,
            @Size(max = 1000) String expression,
            boolean requiredField,
            @Min(0) int sortOrder
    ) {
        /** 转换为应用层命令。 */
        DeviceModelManagementService.ProtocolFieldCommand toCommand() {
            return new DeviceModelManagementService.ProtocolFieldCommand(
                    sourcePath, sourceDataType, targetIdentifier, transformType,
                    scaleFactor, offsetValue, expression, requiredField, sortOrder
            );
        }
    }
}
