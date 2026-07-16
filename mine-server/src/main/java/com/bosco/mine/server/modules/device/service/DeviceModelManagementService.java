package com.bosco.mine.server.modules.device.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.device.repository.DeviceModelManagementMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 处理设备物模型和厂商协议字段映射的草稿、校验与发布用例。
 */
@Service
public class DeviceModelManagementService {

    private static final ErrorCode PRODUCT_NOT_FOUND =
            new ErrorCode("DEVICE-MODEL-404-001", "设备产品不存在或已归档");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("DEVICE-MODEL-404-002", "配置版本不存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("DEVICE-MODEL-409-001", "配置已被其他管理员修改，请刷新后重试");
    private static final ErrorCode CONFIG_CONFLICT =
            new ErrorCode("DEVICE-MODEL-409-002", "字段标识或协议配置版本重复");
    private static final ErrorCode MODEL_INVALID =
            new ErrorCode("DEVICE-MODEL-422-001", "物模型至少需要一个合法字段才能发布");
    private static final ErrorCode MAPPING_INVALID =
            new ErrorCode("DEVICE-MODEL-422-002", "协议映射必须指向当前已发布物模型的字段");
    private static final ErrorCode DRAFT_REQUIRED =
            new ErrorCode("DEVICE-MODEL-422-003", "只有草稿版本允许修改或发布");

    private static final Set<String> MODEL_TYPES =
            Set.of("INTEGER", "DECIMAL", "BOOLEAN", "STRING", "ENUM", "DATETIME", "GEO_POINT");
    private static final Set<String> ACCESS_MODES = Set.of("READ", "WRITE", "READ_WRITE");
    private static final Set<String> PROTOCOL_TYPES = Set.of("MQTT_JSON", "MQTT_BINARY", "TCP_BINARY");
    private static final Set<String> MESSAGE_TYPES = Set.of("TELEMETRY", "STATE", "EVENT", "COMMAND_REPLY");
    private static final Set<String> SOURCE_TYPES =
            Set.of("INTEGER", "DECIMAL", "BOOLEAN", "STRING", "BINARY", "DATETIME");
    private static final Set<String> TRANSFORMS =
            Set.of("NONE", "SCALE", "OFFSET", "SCALE_OFFSET", "ENUM", "EXPRESSION");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][A-Za-z0-9_]{1,127}$");
    private static final Pattern PROFILE_KEY = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");
    private static final Pattern SAFE_EXPRESSION = Pattern.compile("^[A-Za-z0-9_+\\-*/(). ]{1,1000}$");

    private final DeviceModelManagementMapper mapper;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建设备模型管理服务。
     */
    public DeviceModelManagementService(
            DeviceModelManagementMapper mapper,
            OperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 查询一个产品的全部物模型和协议版本及其字段。
     */
    @Transactional(readOnly = true)
    public ProductConfiguration findConfiguration(UUID productId) {
        requireProduct(productId);
        List<ModelVersionView> models = mapper.findModelVersions(productId).stream()
                .map(version -> new ModelVersionView(version, mapper.findModelFields(version.id())))
                .toList();
        List<ProtocolVersionView> protocols = mapper.findProtocolVersions(productId).stream()
                .map(version -> new ProtocolVersionView(version, mapper.findProtocolFields(version.id())))
                .toList();
        return new ProductConfiguration(models, protocols);
    }

    /**
     * 新建一个空物模型草稿版本。
     */
    @Transactional
    public DeviceModelManagementMapper.ModelVersionRecord createModelDraft(UUID productId, String description) {
        requireProduct(productId);
        UUID id = idGenerator.next();
        mapper.insertModelVersion(
                id, productId, mapper.nextModelVersionNumber(productId), normalize(description), currentOperatorId()
        );
        auditService.recordSuccess(
                "DEVICE_MODEL", "CREATE_MODEL_DRAFT", "device:model:edit", "MODEL_VERSION", id,
                Map.of(), Map.of("productId", productId, "status", "DRAFT")
        );
        return requireModel(id);
    }

    /**
     * 原子替换物模型草稿的全部字段。
     */
    @Transactional
    public List<DeviceModelManagementMapper.ModelFieldRecord> replaceModelFields(
            UUID versionId,
            List<ModelFieldCommand> fields
    ) {
        DeviceModelManagementMapper.ModelVersionRecord version = requireDraftModel(versionId);
        validateModelFields(fields);
        mapper.deleteModelFields(versionId);
        UUID operatorId = currentOperatorId();
        try {
            for (ModelFieldCommand field : fields) {
                mapper.insertModelField(
                        idGenerator.next(), versionId, field.identifier().strip(), field.nameZh().strip(),
                        normalize(field.nameEn()), field.dataType(), normalize(field.unit()), field.accessMode(),
                        field.requiredField(), field.minimumValue(), field.maximumValue(), field.decimalPlaces(),
                        field.sortOrder(), normalize(field.description()), operatorId
                );
            }
        } catch (DuplicateKeyException exception) {
            throw new MineException(CONFIG_CONFLICT, Map.of("versionId", versionId));
        }
        auditService.recordSuccess(
                "DEVICE_MODEL", "REPLACE_MODEL_FIELDS", "device:model:edit", "MODEL_VERSION", versionId,
                Map.of(), Map.of("fieldCount", fields.size(), "productId", version.productId())
        );
        return mapper.findModelFields(versionId);
    }

    /**
     * 发布物模型草稿并退役该产品的旧发布版本。
     */
    @Transactional
    public void publishModel(UUID versionId, long versionNumber) {
        DeviceModelManagementMapper.ModelVersionRecord version = requireDraftModel(versionId);
        if (mapper.countModelFields(versionId) == 0) {
            throw new MineException(MODEL_INVALID, Map.of("versionId", versionId));
        }
        UUID operatorId = currentOperatorId();
        mapper.retirePublishedModel(version.productId(), operatorId);
        if (mapper.publishModelVersion(versionId, versionNumber, operatorId) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        auditService.recordSuccess(
                "DEVICE_MODEL", "PUBLISH_MODEL", "device:model:publish", "MODEL_VERSION", versionId,
                Map.of("status", "DRAFT"), Map.of("status", "PUBLISHED", "productId", version.productId())
        );
    }

    /**
     * 新建厂商协议字段映射草稿版本。
     */
    @Transactional
    public DeviceModelManagementMapper.ProtocolVersionRecord createProtocolDraft(
            UUID productId,
            ProtocolDraftCommand command
    ) {
        requireProduct(productId);
        if (!PROFILE_KEY.matcher(command.profileKey()).matches()
                || !PROTOCOL_TYPES.contains(command.protocolType())
                || !MESSAGE_TYPES.contains(command.messageType())) {
            throw new MineException(MAPPING_INVALID, Map.of());
        }
        UUID id = idGenerator.next();
        try {
            mapper.insertProtocolVersion(
                    id, productId, command.profileKey(), command.name().strip(), command.protocolType(),
                    command.messageType(), mapper.nextProtocolVersionNumber(productId, command.profileKey()),
                    normalize(command.topicOrFrame()), normalize(command.description()), currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(CONFIG_CONFLICT, Map.of("profileKey", command.profileKey()));
        }
        auditService.recordSuccess(
                "DEVICE_MODEL", "CREATE_PROTOCOL_DRAFT", "device:model:edit", "PROTOCOL_VERSION", id,
                Map.of(), Map.of("productId", productId, "profileKey", command.profileKey())
        );
        return requireProtocol(id);
    }

    /**
     * 原子替换协议草稿的全部字段映射。
     */
    @Transactional
    public List<DeviceModelManagementMapper.ProtocolFieldRecord> replaceProtocolFields(
            UUID versionId,
            List<ProtocolFieldCommand> fields
    ) {
        requireDraftProtocol(versionId);
        validateProtocolFields(fields);
        mapper.deleteProtocolFields(versionId);
        UUID operatorId = currentOperatorId();
        try {
            for (ProtocolFieldCommand field : fields) {
                mapper.insertProtocolField(
                        idGenerator.next(), versionId, field.sourcePath().strip(), field.sourceDataType(),
                        field.targetIdentifier().strip(), field.transformType(), field.scaleFactor(),
                        field.offsetValue(), normalize(field.expression()), field.requiredField(),
                        field.sortOrder(), operatorId
                );
            }
        } catch (DuplicateKeyException exception) {
            throw new MineException(CONFIG_CONFLICT, Map.of("versionId", versionId));
        }
        return mapper.findProtocolFields(versionId);
    }

    /**
     * 发布协议映射草稿并退役同一配置编码的旧发布版本。
     */
    @Transactional
    public void publishProtocol(UUID versionId, long versionNumber) {
        DeviceModelManagementMapper.ProtocolVersionRecord version = requireDraftProtocol(versionId);
        List<DeviceModelManagementMapper.ProtocolFieldRecord> fields = mapper.findProtocolFields(versionId);
        List<String> identifiers = fields.stream()
                .map(DeviceModelManagementMapper.ProtocolFieldRecord::targetIdentifier).distinct().toList();
        if (identifiers.isEmpty()
                || mapper.countPublishedModelIdentifiers(version.productId(), identifiers) != identifiers.size()) {
            throw new MineException(MAPPING_INVALID, Map.of("versionId", versionId));
        }
        UUID operatorId = currentOperatorId();
        mapper.retirePublishedProtocol(version.productId(), version.profileKey(), operatorId);
        if (mapper.publishProtocolVersion(versionId, versionNumber, operatorId) == 0) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        auditService.recordSuccess(
                "DEVICE_MODEL", "PUBLISH_PROTOCOL", "device:model:publish", "PROTOCOL_VERSION", versionId,
                Map.of("status", "DRAFT"), Map.of("status", "PUBLISHED", "profileKey", version.profileKey())
        );
    }

    private void validateModelFields(List<ModelFieldCommand> fields) {
        if (fields.isEmpty()) throw new MineException(MODEL_INVALID, Map.of());
        Set<String> identifiers = new HashSet<>();
        for (ModelFieldCommand field : fields) {
            if (!IDENTIFIER.matcher(field.identifier()).matches()
                    || !MODEL_TYPES.contains(field.dataType())
                    || !ACCESS_MODES.contains(field.accessMode())
                    || field.sortOrder() < 0
                    || !identifiers.add(field.identifier())) {
                throw new MineException(MODEL_INVALID, Map.of("identifier", field.identifier()));
            }
        }
    }

    private void validateProtocolFields(List<ProtocolFieldCommand> fields) {
        if (fields.isEmpty()) throw new MineException(MAPPING_INVALID, Map.of());
        Set<String> sourcePaths = new HashSet<>();
        for (ProtocolFieldCommand field : fields) {
            boolean expressionInvalid = "EXPRESSION".equals(field.transformType())
                    && (field.expression() == null || !SAFE_EXPRESSION.matcher(field.expression()).matches());
            if (field.sourcePath().isBlank() || !sourcePaths.add(field.sourcePath())
                    || !SOURCE_TYPES.contains(field.sourceDataType())
                    || !IDENTIFIER.matcher(field.targetIdentifier()).matches()
                    || !TRANSFORMS.contains(field.transformType()) || expressionInvalid
                    || field.sortOrder() < 0) {
                throw new MineException(MAPPING_INVALID, Map.of("sourcePath", field.sourcePath()));
            }
        }
    }

    private void requireProduct(UUID productId) {
        if (!mapper.productExists(productId)) {
            throw new MineException(PRODUCT_NOT_FOUND, Map.of("productId", productId));
        }
    }

    private DeviceModelManagementMapper.ModelVersionRecord requireModel(UUID id) {
        return mapper.findModelVersion(id)
                .orElseThrow(() -> new MineException(VERSION_NOT_FOUND, Map.of("versionId", id)));
    }

    private DeviceModelManagementMapper.ModelVersionRecord requireDraftModel(UUID id) {
        DeviceModelManagementMapper.ModelVersionRecord version = requireModel(id);
        if (!"DRAFT".equals(version.status())) throw new MineException(DRAFT_REQUIRED, Map.of("versionId", id));
        return version;
    }

    private DeviceModelManagementMapper.ProtocolVersionRecord requireProtocol(UUID id) {
        return mapper.findProtocolVersion(id)
                .orElseThrow(() -> new MineException(VERSION_NOT_FOUND, Map.of("versionId", id)));
    }

    private DeviceModelManagementMapper.ProtocolVersionRecord requireDraftProtocol(UUID id) {
        DeviceModelManagementMapper.ProtocolVersionRecord version = requireProtocol(id);
        if (!"DRAFT".equals(version.status())) throw new MineException(DRAFT_REQUIRED, Map.of("versionId", id));
        return version;
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 产品配置聚合视图。 */
    public record ProductConfiguration(List<ModelVersionView> models, List<ProtocolVersionView> protocols) {
    }

    /** 带字段的物模型版本视图。 */
    public record ModelVersionView(
            DeviceModelManagementMapper.ModelVersionRecord version,
            List<DeviceModelManagementMapper.ModelFieldRecord> fields
    ) {
    }

    /** 带字段的协议映射版本视图。 */
    public record ProtocolVersionView(
            DeviceModelManagementMapper.ProtocolVersionRecord version,
            List<DeviceModelManagementMapper.ProtocolFieldRecord> fields
    ) {
    }

    /** 物模型字段保存命令。 */
    public record ModelFieldCommand(
            String identifier, String nameZh, String nameEn, String dataType, String unit,
            String accessMode, boolean requiredField, BigDecimal minimumValue,
            BigDecimal maximumValue, Integer decimalPlaces, int sortOrder, String description
    ) {
    }

    /** 协议映射草稿创建命令。 */
    public record ProtocolDraftCommand(
            String profileKey, String name, String protocolType, String messageType,
            String topicOrFrame, String description
    ) {
    }

    /** 协议字段映射保存命令。 */
    public record ProtocolFieldCommand(
            String sourcePath, String sourceDataType, String targetIdentifier,
            String transformType, BigDecimal scaleFactor, BigDecimal offsetValue,
            String expression, boolean requiredField, int sortOrder
    ) {
    }
}
