package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.system.repository.DictionaryManagementMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理系统通用字典类型和数据项的管理与读取用例。
 */
@Service
public class DictionaryManagementService {

    private static final ErrorCode TYPE_NOT_FOUND =
            new ErrorCode("SYSTEM-404-004", "字典类型不存在");
    private static final ErrorCode ITEM_NOT_FOUND =
            new ErrorCode("SYSTEM-404-005", "字典数据项不存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("SYSTEM-409-003", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode DICTIONARY_CONFLICT =
            new ErrorCode("SYSTEM-409-007", "字典编码或数据值已存在");
    private static final ErrorCode DICTIONARY_IN_USE =
            new ErrorCode("SYSTEM-409-008", "内置字典或含数据项的字典类型不能删除");
    private static final ErrorCode DICTIONARY_INVALID =
            new ErrorCode("SYSTEM-422-003", "字典配置不合法");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SEMANTIC_TYPES =
            Set.of("default", "primary", "info", "success", "warning", "error");

    private final DictionaryManagementMapper mapper;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final Map<String, List<DictionaryManagementMapper.DictItemRecord>> enabledItemCache =
            new ConcurrentHashMap<>();

    /**
     * 创建字典管理服务。
     *
     * @param mapper       字典数据接口
     * @param auditService 操作审计服务
     */
    public DictionaryManagementService(
            DictionaryManagementMapper mapper,
            OperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 查询全部字典类型。
     */
    @Transactional(readOnly = true)
    public List<DictionaryManagementMapper.DictTypeRecord> findTypes() {
        return mapper.findAllTypes();
    }

    /**
     * 创建字典类型，编码创建后不可修改。
     */
    @Transactional
    public DictionaryManagementMapper.DictTypeRecord createType(DictTypeCommand command) {
        String code = command.code().strip().toLowerCase(Locale.ROOT);
        String status = normalizeStatus(command.status());
        UUID id = idGenerator.next();
        try {
            mapper.insertType(
                    id, code, command.name().strip(), status,
                    normalize(command.description()), currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(DICTIONARY_CONFLICT, Map.of("code", code));
        }
        DictionaryManagementMapper.DictTypeRecord created = requireType(id);
        auditService.recordSuccess(
                "CREATE_DICTIONARY_TYPE", "system:dictionary:create", "DICTIONARY_TYPE", id,
                Map.of(), typeSummary(created)
        );
        return created;
    }

    /**
     * 修改字典类型可变属性。
     */
    @Transactional
    public DictionaryManagementMapper.DictTypeRecord updateType(
            UUID id,
            String name,
            String status,
            String description,
            long version
    ) {
        DictionaryManagementMapper.DictTypeRecord before = requireType(id);
        int affected = mapper.updateType(
                id, name.strip(), normalizeStatus(status), normalize(description), version, currentOperatorId()
        );
        if (affected == 0) throw versionConflict(id);
        invalidate(before.code());
        DictionaryManagementMapper.DictTypeRecord updated = requireType(id);
        auditService.recordSuccess(
                "UPDATE_DICTIONARY_TYPE", "system:dictionary:update", "DICTIONARY_TYPE", id,
                typeSummary(before), typeSummary(updated)
        );
        return updated;
    }

    /**
     * 删除非内置且不含数据项的字典类型。
     */
    @Transactional
    public void deleteType(UUID id) {
        DictionaryManagementMapper.DictTypeRecord before = requireType(id);
        if (before.builtIn() || mapper.countItems(id) > 0) {
            throw new MineException(DICTIONARY_IN_USE, Map.of("typeId", id));
        }
        mapper.deleteType(id);
        invalidate(before.code());
        auditService.recordSuccess(
                "DELETE_DICTIONARY_TYPE", "system:dictionary:delete", "DICTIONARY_TYPE", id,
                typeSummary(before), Map.of()
        );
    }

    /**
     * 查询指定类型的管理端数据项。
     */
    @Transactional(readOnly = true)
    public List<DictionaryManagementMapper.DictItemRecord> findItems(UUID typeId) {
        requireType(typeId);
        return mapper.findItemsByTypeId(typeId);
    }

    /**
     * 按稳定编码读取启用数据项，并使用按编码精确失效的本地缓存。
     */
    @Transactional(readOnly = true)
    public List<DictionaryManagementMapper.DictItemRecord> findEnabledItems(String code) {
        String normalizedCode = code.strip().toLowerCase(Locale.ROOT);
        return enabledItemCache.computeIfAbsent(normalizedCode, key ->
                List.copyOf(mapper.findEnabledItemsByCode(key)));
    }

    /**
     * 创建字典数据项，值创建后不可修改。
     */
    @Transactional
    public DictionaryManagementMapper.DictItemRecord createItem(UUID typeId, DictItemCommand command) {
        DictionaryManagementMapper.DictTypeRecord type = requireType(typeId);
        validateItem(command);
        if (command.defaultItem()) mapper.clearDefaultItem(typeId, null);
        UUID id = idGenerator.next();
        try {
            mapper.insertItem(
                    id, typeId, command.value().strip(), command.labelZh().strip(), normalize(command.labelEn()),
                    command.semanticType(), command.sortOrder(), command.defaultItem(),
                    normalizeStatus(command.status()), currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(DICTIONARY_CONFLICT, Map.of("value", command.value().strip()));
        }
        invalidate(type.code());
        DictionaryManagementMapper.DictItemRecord created = requireItem(id);
        auditService.recordSuccess(
                "CREATE_DICTIONARY_ITEM", "system:dictionary:create", "DICTIONARY_ITEM", id,
                Map.of(), itemSummary(created)
        );
        return created;
    }

    /**
     * 修改字典数据项可变属性。
     */
    @Transactional
    public DictionaryManagementMapper.DictItemRecord updateItem(
            UUID id,
            DictItemUpdateCommand command
    ) {
        DictionaryManagementMapper.DictItemRecord before = requireItem(id);
        DictionaryManagementMapper.DictTypeRecord type = requireType(before.typeId());
        validateItem(new DictItemCommand(
                before.value(), command.labelZh(), command.labelEn(), command.semanticType(), command.sortOrder(),
                command.defaultItem(), command.status()
        ));
        if (command.defaultItem()) mapper.clearDefaultItem(before.typeId(), id);
        int affected = mapper.updateItem(
                id, command.labelZh().strip(), normalize(command.labelEn()), command.semanticType(),
                command.sortOrder(), command.defaultItem(), normalizeStatus(command.status()),
                command.version(), currentOperatorId()
        );
        if (affected == 0) throw versionConflict(id);
        invalidate(type.code());
        DictionaryManagementMapper.DictItemRecord updated = requireItem(id);
        auditService.recordSuccess(
                "UPDATE_DICTIONARY_ITEM", "system:dictionary:update", "DICTIONARY_ITEM", id,
                itemSummary(before), itemSummary(updated)
        );
        return updated;
    }

    /**
     * 删除字典数据项。
     */
    @Transactional
    public void deleteItem(UUID id) {
        DictionaryManagementMapper.DictItemRecord before = requireItem(id);
        DictionaryManagementMapper.DictTypeRecord type = requireType(before.typeId());
        mapper.deleteItem(id);
        invalidate(type.code());
        auditService.recordSuccess(
                "DELETE_DICTIONARY_ITEM", "system:dictionary:delete", "DICTIONARY_ITEM", id,
                itemSummary(before), Map.of()
        );
    }

    private void validateItem(DictItemCommand command) {
        if (!SEMANTIC_TYPES.contains(command.semanticType()) || command.sortOrder() < 0) {
            throw new MineException(DICTIONARY_INVALID, Map.of());
        }
        normalizeStatus(command.status());
    }

    private String normalizeStatus(String value) {
        String status = value.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new MineException(DICTIONARY_INVALID, Map.of());
        return status;
    }

    private DictionaryManagementMapper.DictTypeRecord requireType(UUID id) {
        return mapper.findTypeById(id)
                .orElseThrow(() -> new MineException(TYPE_NOT_FOUND, Map.of("typeId", id)));
    }

    private DictionaryManagementMapper.DictItemRecord requireItem(UUID id) {
        return mapper.findItemById(id)
                .orElseThrow(() -> new MineException(ITEM_NOT_FOUND, Map.of("itemId", id)));
    }

    private MineException versionConflict(UUID id) {
        return new MineException(VERSION_CONFLICT, Map.of("resourceId", id));
    }

    private void invalidate(String code) {
        enabledItemCache.remove(code);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, ?> typeSummary(DictionaryManagementMapper.DictTypeRecord type) {
        return Map.of("code", type.code(), "name", type.name(), "status", type.status());
    }

    private Map<String, ?> itemSummary(DictionaryManagementMapper.DictItemRecord item) {
        return Map.of(
                "typeId", item.typeId(), "value", item.value(), "labelZh", item.labelZh(),
                "semanticType", item.semanticType(), "status", item.status()
        );
    }

    /**
     * 字典类型创建命令。
     */
    public record DictTypeCommand(String code, String name, String status, String description) {
    }

    /**
     * 字典数据项创建命令。
     */
    public record DictItemCommand(
            String value,
            String labelZh,
            String labelEn,
            String semanticType,
            int sortOrder,
            boolean defaultItem,
            String status
    ) {
    }

    /**
     * 字典数据项修改命令，稳定值不允许修改。
     */
    public record DictItemUpdateCommand(
            String labelZh,
            String labelEn,
            String semanticType,
            int sortOrder,
            boolean defaultItem,
            String status,
            long version
    ) {
    }
}
