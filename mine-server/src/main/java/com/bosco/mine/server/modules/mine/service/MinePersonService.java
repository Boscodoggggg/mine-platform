package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationRequest;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationResponse;
import com.bosco.mine.server.modules.mine.dto.PersonCreateRequest;
import com.bosco.mine.server.modules.mine.dto.PersonResponse;
import com.bosco.mine.server.modules.mine.dto.PersonSensitiveResponse;
import com.bosco.mine.server.modules.mine.dto.PersonSensitiveUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.PersonUpdateRequest;
import com.bosco.mine.server.modules.mine.model.MineDriverQualification;
import com.bosco.mine.server.modules.mine.model.MinePerson;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.repository.MinePersonMapper;
import com.bosco.mine.server.modules.mine.security.SensitiveFieldCipher;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理人员档案、司机资质以及独立审计的敏感字段读取。
 */
@Service
public class MinePersonService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> PERSON_TYPES = Set.of(
            "DRIVER", "OPERATOR", "SUPERVISOR", "WORKER", "VISITOR", "OTHER");
    private static final Set<String> VEHICLE_TYPES = Set.of(
            "HAUL_TRUCK", "EXCAVATOR", "LOADER", "DRILL", "DOZER",
            "WATER_TRUCK", "LIGHT_VEHICLE", "OTHER");
    private static final Set<String> PERSON_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private static final ErrorCode PERSON_INVALID =
            new ErrorCode("MINE-PERSON-422-001", "人员档案参数不合法");
    private static final ErrorCode QUALIFICATION_INVALID =
            new ErrorCode("MINE-PERSON-422-002", "司机资质参数不合法");
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("MINE-PERSON-422-003", "人员分页参数不合法");
    private static final ErrorCode PERSON_NOT_FOUND =
            new ErrorCode("MINE-PERSON-404-001", "人员不存在或不属于当前矿区");
    private static final ErrorCode QUALIFICATION_NOT_FOUND =
            new ErrorCode("MINE-PERSON-404-002", "司机资质不存在或不属于当前人员");
    private static final ErrorCode PERSON_CODE_CONFLICT =
            new ErrorCode("MINE-PERSON-409-001", "组织内人员编码已存在");
    private static final ErrorCode PERSON_VERSION_CONFLICT =
            new ErrorCode("MINE-PERSON-409-002", "人员档案已被其他管理员修改");
    private static final ErrorCode QUALIFICATION_CONFLICT =
            new ErrorCode("MINE-PERSON-409-003", "司机资质编码已存在或已被修改");

    private final MinePersonMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final SensitiveFieldCipher cipher;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建人员和资质服务。
     *
     * @param mapper 人员数据访问接口
     * @param authorizer 矿区数据作用域授权器
     * @param cipher 敏感字段加密器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public MinePersonService(
            MinePersonMapper mapper,
            MineScopeAuthorizer authorizer,
            SensitiveFieldCipher cipher,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 创建加密存储敏感字段的人员档案。 */
    @Transactional
    public PersonResponse createPerson(UUID siteId, PersonCreateRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        validatePerson(request.name(), request.personType(), "ACTIVE");
        validateAssignment(request.assignmentFrom(), request.assignmentTo());
        validateSensitive(request.phone(), request.certificateNumber());
        UUID id = idGenerator.next();
        UUID operatorId = authorizer.currentUserId();
        try {
            mapper.insertPerson(
                    id, site.organizationId(), normalizeCode(request.personCode()),
                    required(request.name()), upper(request.personType()),
                    cipher.encrypt(id, SensitiveFieldCipher.FieldType.PHONE, required(request.phone())),
                    maskPhone(request.phone()),
                    cipher.encrypt(id, SensitiveFieldCipher.FieldType.CERTIFICATE,
                            required(request.certificateNumber())),
                    maskCertificate(request.certificateNumber()), operatorId);
            mapper.insertSiteAssignment(
                    idGenerator.next(), site.organizationId(), site.id(), id,
                    startOfDay(request.assignmentFrom(), site.timezone()),
                    endExclusive(request.assignmentTo(), site.timezone()),
                    "INITIAL_EMPLOYMENT", operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(PERSON_CODE_CONFLICT, Map.of());
        } catch (DataIntegrityViolationException exception) {
            throw invalidPerson();
        }
        PersonResponse created = response(requireHistoricalPerson(site, id));
        audit("CREATE_PERSON", "mine:person:edit", "MINE_PERSON", id,
                Map.of(), personSummary(created));
        return created;
    }

    /** 分页查询仅包含脱敏字段的人员档案。 */
    @Transactional(readOnly = true)
    public PageResponse<PersonResponse> findPeople(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new MineException(PAGE_INVALID, Map.of("page", page, "size", size));
        }
        MineSite site = authorizer.requireSite(siteId);
        String normalizedStatus = optionalUpper(status);
        if (normalizedStatus != null
                && !PERSON_STATUSES.contains(normalizedStatus)
                && !"ARCHIVED".equals(normalizedStatus)) {
            throw invalidPerson();
        }
        String normalizedKeyword = optional(keyword);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findPeople(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size, site.timezone())
                        .stream().map(MinePersonService::response).toList(),
                page,
                size,
                mapper.countPeople(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 查询仅包含脱敏字段的人员详情。 */
    @Transactional(readOnly = true)
    public PersonResponse findPerson(UUID siteId, UUID personId) {
        MineSite site = authorizer.requireSite(siteId);
        return response(requireHistoricalPerson(site, personId));
    }

    /** 使用乐观锁修改人员普通档案并保留已有敏感密文。 */
    @Transactional
    public PersonResponse updatePerson(UUID siteId, UUID personId, PersonUpdateRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        MinePerson before = requireCurrentPerson(site, personId);
        validatePerson(request.name(), request.personType(), request.status());
        try {
            int changed = mapper.updatePerson(
                    site.organizationId(), site.id(), personId, required(request.name()),
                    upper(request.personType()), upper(request.status()), request.version(),
                    authorizer.currentUserId());
            if (changed == 0) {
                throw new MineException(PERSON_VERSION_CONFLICT, Map.of("personId", personId));
            }
        } catch (DataIntegrityViolationException exception) {
            throw invalidPerson();
        }
        PersonResponse updated = response(requireCurrentPerson(site, personId));
        audit("UPDATE_PERSON", "mine:person:edit", "MINE_PERSON", personId,
                personSummary(response(before)), personSummary(updated));
        return updated;
    }

    /** 使用独立权限和乐观锁更新人员敏感字段。 */
    @Transactional
    public PersonResponse updateSensitive(
            UUID siteId, UUID personId, PersonSensitiveUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        MinePerson before = requireCurrentPerson(site, personId);
        validateSensitive(request.phone(), request.certificateNumber());
        String phone = required(request.phone());
        String certificate = required(request.certificateNumber());
        int changed = mapper.updateSensitive(
                site.organizationId(), site.id(), personId,
                cipher.encrypt(personId, SensitiveFieldCipher.FieldType.PHONE, phone),
                maskPhone(phone),
                cipher.encrypt(personId, SensitiveFieldCipher.FieldType.CERTIFICATE, certificate),
                maskCertificate(certificate), request.version(), authorizer.currentUserId());
        if (changed == 0) {
            throw new MineException(PERSON_VERSION_CONFLICT, Map.of("personId", personId));
        }
        PersonResponse updated = response(requireCurrentPerson(site, personId));
        audit("UPDATE_PERSON_SENSITIVE", "mine:person:sensitive:edit", "MINE_PERSON", personId,
                Map.of(
                        "phoneMasked", before.phoneMasked(),
                        "certificateMasked", before.certificateMasked()),
                Map.of(
                        "phoneMasked", updated.phoneMasked(),
                        "certificateMasked", updated.certificateMasked()));
        return updated;
    }

    /** 使用乐观锁归档人员。 */
    @Transactional
    public void archivePerson(UUID siteId, UUID personId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        MinePerson before = requireCurrentPerson(site, personId);
        if (mapper.archivePerson(
                site.organizationId(), site.id(), personId, version,
                authorizer.currentUserId()) == 0) {
            throw new MineException(PERSON_VERSION_CONFLICT, Map.of("personId", personId));
        }
        audit("ARCHIVE_PERSON", "mine:person:edit", "MINE_PERSON", personId,
                personSummary(response(before)), Map.of("status", "ARCHIVED"));
    }

    /**
     * 显式解密人员敏感字段并写入独立读取审计。
     *
     * @return 仅供敏感权限接口返回的明文响应
     */
    @Transactional
    public PersonSensitiveResponse readSensitive(UUID siteId, UUID personId) {
        MineSite site = authorizer.requireSite(siteId);
        MinePerson person = requireCurrentPerson(site, personId);
        PersonSensitiveResponse result = new PersonSensitiveResponse(
                person.id(),
                cipher.decrypt(
                        person.id(), SensitiveFieldCipher.FieldType.PHONE, person.phoneCiphertext()),
                cipher.decrypt(
                        person.id(), SensitiveFieldCipher.FieldType.CERTIFICATE,
                        person.certificateCiphertext()));
        audit("READ_PERSON_SENSITIVE", "mine:person:sensitive:view", "MINE_PERSON", personId,
                Map.of(), Map.of("siteId", site.id(), "personId", person.id(), "result", "GRANTED"));
        return result;
    }

    /** 新增受车型和有效期约束的司机资质。 */
    @Transactional
    public DriverQualificationResponse addQualification(
            UUID siteId, UUID personId, DriverQualificationRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireCurrentPerson(site, personId);
        List<String> vehicleTypes = validateQualification(request);
        UUID id = idGenerator.next();
        try {
            mapper.insertQualification(
                    id, site.organizationId(), personId,
                    normalizeCode(request.qualificationCode()), required(request.name()),
                    writeJson(vehicleTypes), request.validFrom(), request.validTo(),
                    authorizer.currentUserId());
        } catch (DuplicateKeyException exception) {
            throw new MineException(QUALIFICATION_CONFLICT, Map.of("personId", personId));
        } catch (DataIntegrityViolationException exception) {
            throw invalidQualification();
        }
        DriverQualificationResponse created = qualification(
                requireQualification(site, personId, id));
        audit("CREATE_DRIVER_QUALIFICATION", "mine:person:qualification:edit",
                "MINE_DRIVER_QUALIFICATION", id, Map.of(), qualificationSummary(created));
        return created;
    }

    /** 查询人员全部司机资质。 */
    @Transactional(readOnly = true)
    public List<DriverQualificationResponse> findQualifications(UUID siteId, UUID personId) {
        MineSite site = authorizer.requireSite(siteId);
        requireHistoricalPerson(site, personId);
        return mapper.findQualifications(site.organizationId(), personId)
                .stream().map(this::qualification).toList();
    }

    /** 使用乐观锁吊销司机资质。 */
    @Transactional
    public void revokeQualification(
            UUID siteId, UUID personId, UUID qualificationId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireCurrentPerson(site, personId);
        DriverQualificationResponse before = qualification(
                requireQualification(site, personId, qualificationId));
        if (mapper.revokeQualification(
                site.organizationId(), personId, qualificationId, version,
                authorizer.currentUserId()) == 0) {
            throw new MineException(QUALIFICATION_CONFLICT, Map.of("qualificationId", qualificationId));
        }
        audit("REVOKE_DRIVER_QUALIFICATION", "mine:person:qualification:edit",
                "MINE_DRIVER_QUALIFICATION", qualificationId,
                qualificationSummary(before), Map.of("status", "REVOKED"));
    }

    private MinePerson requireHistoricalPerson(MineSite site, UUID personId) {
        return mapper.findPerson(
                        site.organizationId(), site.id(), personId, site.timezone())
                .orElseThrow(() -> new MineException(PERSON_NOT_FOUND, Map.of("personId", personId)));
    }

    private MinePerson requireCurrentPerson(MineSite site, UUID personId) {
        mapper.lockPersonTimeline(personId);
        return mapper.findCurrentPerson(
                        site.organizationId(), site.id(), personId, site.timezone())
                .orElseThrow(() -> new MineException(PERSON_NOT_FOUND, Map.of("personId", personId)));
    }

    private MineDriverQualification requireQualification(
            MineSite site, UUID personId, UUID qualificationId
    ) {
        return mapper.findQualification(
                        site.organizationId(), personId, qualificationId)
                .orElseThrow(() -> new MineException(
                        QUALIFICATION_NOT_FOUND, Map.of("qualificationId", qualificationId)));
    }

    private void validatePerson(String name, String personType, String status) {
        if (blank(name) || name.trim().length() > 128
                || !PERSON_TYPES.contains(upper(personType))
                || !PERSON_STATUSES.contains(upper(status))) {
            throw invalidPerson();
        }
    }

    private void validateAssignment(LocalDate assignmentFrom, LocalDate assignmentTo) {
        if (assignmentFrom == null
                || (assignmentTo != null && assignmentTo.isBefore(assignmentFrom))) {
            throw invalidPerson();
        }
    }

    private void validateSensitive(String phone, String certificate) {
        if (blank(phone) || blank(certificate)
                || phone.trim().length() > 32 || certificate.trim().length() > 64) {
            throw invalidPerson();
        }
    }

    private List<String> validateQualification(DriverQualificationRequest request) {
        if (request == null || blank(request.qualificationCode()) || blank(request.name())
                || request.name().trim().length() > 128 || request.validFrom() == null
                || request.validTo() == null || request.validTo().isBefore(request.validFrom())
                || request.allowedVehicleTypes() == null || request.allowedVehicleTypes().isEmpty()
                || request.allowedVehicleTypes().size() > 8) {
            throw invalidQualification();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : request.allowedVehicleTypes()) {
            String vehicleType = upper(value);
            if (!VEHICLE_TYPES.contains(vehicleType) || !normalized.add(vehicleType)) {
                throw invalidQualification();
            }
        }
        return List.copyOf(normalized);
    }

    private DriverQualificationResponse qualification(MineDriverQualification value) {
        try {
            return new DriverQualificationResponse(
                    value.id(), value.personId(), value.qualificationCode(), value.name(),
                    List.of(objectMapper.readValue(value.allowedVehicleTypesJson(), String[].class)),
                    value.validFrom(), value.validTo(), value.status(), value.revokedAt(),
                    value.createdAt(), value.updatedAt(), value.version());
        } catch (JacksonException exception) {
            throw new IllegalStateException("司机资质车型数据损坏", exception);
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("司机资质车型序列化失败", exception);
        }
    }

    private static PersonResponse response(MinePerson person) {
        return new PersonResponse(
                person.id(), person.organizationId(), person.siteId(), person.personCode(),
                person.name(), person.personType(), person.phoneMasked(), person.certificateMasked(),
                person.assignmentFrom(), person.assignmentTo(), person.status(), person.archivedAt(),
                person.createdAt(), person.updatedAt(), person.version());
    }

    private Map<String, ?> personSummary(PersonResponse person) {
        return Map.of(
                "personCode", person.personCode(),
                "personType", person.personType(),
                "status", person.status());
    }

    private Map<String, ?> qualificationSummary(DriverQualificationResponse qualification) {
        return Map.of(
                "personId", qualification.personId(),
                "qualificationCode", qualification.qualificationCode(),
                "allowedVehicleTypes", qualification.allowedVehicleTypes(),
                "status", qualification.status());
    }

    private void audit(
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "MINE_MANAGEMENT", action, permission, resourceType, resourceId, before, after);
    }

    private String maskPhone(String value) {
        String normalized = required(value);
        if (normalized.length() <= 7) {
            return "*".repeat(normalized.length());
        }
        return normalized.substring(0, 3)
                + "*".repeat(normalized.length() - 7)
                + normalized.substring(normalized.length() - 4);
    }

    private String maskCertificate(String value) {
        String normalized = required(value);
        if (normalized.length() <= 7) {
            return "*".repeat(normalized.length());
        }
        return normalized.substring(0, 3)
                + "*".repeat(normalized.length() - 7)
                + normalized.substring(normalized.length() - 4);
    }

    private String normalizeCode(String value) {
        String normalized = required(value).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9-]{1,63}")) {
            throw invalidPerson();
        }
        return normalized;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String optionalUpper(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String optional(String value) {
        return blank(value) ? null : value.trim();
    }

    private String required(String value) {
        if (blank(value)) {
            throw invalidPerson();
        }
        return value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private MineException invalidPerson() {
        return new MineException(PERSON_INVALID, Map.of());
    }

    private MineException invalidQualification() {
        return new MineException(QUALIFICATION_INVALID, Map.of());
    }

    private Instant startOfDay(LocalDate date, String timezone) {
        return date.atStartOfDay(ZoneId.of(timezone)).toInstant();
    }

    private Instant endExclusive(LocalDate date, String timezone) {
        return date == null ? null : startOfDay(date.plusDays(1), timezone);
    }
}
