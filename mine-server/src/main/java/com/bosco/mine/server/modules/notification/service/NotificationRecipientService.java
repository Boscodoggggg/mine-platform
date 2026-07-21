package com.bosco.mine.server.modules.notification.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientTargetResponse;
import com.bosco.mine.server.modules.notification.model.NotificationRecipient;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientGroup;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientType;
import com.bosco.mine.server.modules.notification.repository.NotificationRecipientMapper;
import com.bosco.mine.server.modules.notification.security.NotificationSecretCipher;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理通知接收组和经过加密保护的接收目标。
 */
@Service
public class NotificationRecipientService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9][0-9]{6,14}$");
    private static final Pattern STATUS_PATTERN = Pattern.compile("ENABLED|DISABLED");
    private static final int MAX_TARGET_LENGTH = 2048;

    private static final ErrorCode GROUP_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-011", "通知接收组不存在");
    private static final ErrorCode RECIPIENT_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-012", "通知接收对象不存在");
    private static final ErrorCode GROUP_CONFLICT =
            new ErrorCode("NOTIFICATION-409-011", "接收组已被其他管理员修改，请刷新后重试");
    private static final ErrorCode RECIPIENT_CONFLICT =
            new ErrorCode("NOTIFICATION-409-012", "接收对象已被其他管理员修改，请刷新后重试");
    private static final ErrorCode GROUP_DUPLICATE =
            new ErrorCode("NOTIFICATION-409-013", "通知接收组编码已经存在");
    private static final ErrorCode TARGET_DUPLICATE =
            new ErrorCode("NOTIFICATION-409-014", "接收组内相同渠道目标已经存在");
    private static final ErrorCode RECIPIENT_INVALID =
            new ErrorCode("NOTIFICATION-400-011", "通知接收对象配置不合法");

    private final NotificationRecipientMapper mapper;
    private final NotificationSecretCipher cipher;
    private final OperationAuditService auditService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建通知接收对象管理服务。
     *
     * @param mapper 接收对象数据接口
     * @param cipher 通知独立密钥加密器
     * @param auditService 操作审计服务
     */
    public NotificationRecipientService(
            NotificationRecipientMapper mapper,
            NotificationSecretCipher cipher,
            OperationAuditService auditService
    ) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.auditService = auditService;
    }

    /**
     * 查询全部通知接收组。
     *
     * @return 接收组列表
     */
    @Transactional(readOnly = true)
    public List<NotificationRecipientGroupResponse> listGroups() {
        return mapper.findGroups().stream().map(this::toGroupResponse).toList();
    }

    /**
     * 查询指定通知接收组。
     *
     * @param groupId 接收组标识
     * @return 接收组
     */
    @Transactional(readOnly = true)
    public NotificationRecipientGroupResponse getGroup(UUID groupId) {
        return toGroupResponse(requireGroup(groupId));
    }

    /**
     * 创建通知接收组。
     *
     * @param request 创建请求
     * @return 创建后的接收组
     */
    @Transactional
    public NotificationRecipientGroupResponse createGroup(
            NotificationRecipientGroupRequest request
    ) {
        if (mapper.findGroupByCode(request.groupCode()).isPresent()) {
            throw duplicateGroup(request.groupCode());
        }
        validateGroupRequest(request);
        Instant now = Instant.now();
        NotificationRecipientGroup group = new NotificationRecipientGroup(
                idGenerator.next(), request.groupCode(), request.name().strip(),
                normalizeNullable(request.description()), request.status(), now, now, 0);
        try {
            if (mapper.insertGroup(group, currentOperatorId()) != 1) {
                throw new IllegalStateException("通知接收组写入行数异常");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateGroup(request.groupCode());
        }
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_RECIPIENT_GROUP",
                "notification:recipient:edit", "NOTIFICATION_RECIPIENT_GROUP",
                group.id(), Map.of(), groupSummary(group));
        return toGroupResponse(group);
    }

    /**
     * 使用乐观锁修改通知接收组。
     *
     * @param groupId 接收组标识
     * @param request 修改请求
     * @return 修改后的接收组
     */
    @Transactional
    public NotificationRecipientGroupResponse updateGroup(
            UUID groupId,
            NotificationRecipientGroupRequest request
    ) {
        NotificationRecipientGroup existing = requireGroup(groupId);
        if (!existing.groupCode().equals(request.groupCode())) {
            throw invalid("GROUP_CODE_IMMUTABLE");
        }
        if (existing.version() != request.version()) {
            throw groupConflict(groupId);
        }
        validateGroupRequest(request);
        NotificationRecipientGroup candidate = new NotificationRecipientGroup(
                groupId, existing.groupCode(), request.name().strip(),
                normalizeNullable(request.description()), request.status(),
                existing.createdAt(), Instant.now(), existing.version() + 1);
        if (mapper.updateGroup(candidate, request.version(), currentOperatorId()) != 1) {
            throw groupConflict(groupId);
        }
        auditService.recordSuccess(
                "NOTIFICATION", "UPDATE_NOTIFICATION_RECIPIENT_GROUP",
                "notification:recipient:edit", "NOTIFICATION_RECIPIENT_GROUP",
                groupId, groupSummary(existing), groupSummary(candidate));
        return toGroupResponse(candidate);
    }

    /**
     * 查询接收组内全部接收对象，响应只包含脱敏目标。
     *
     * @param groupId 接收组标识
     * @return 接收对象列表
     */
    @Transactional(readOnly = true)
    public List<NotificationRecipientResponse> listRecipients(UUID groupId) {
        requireGroup(groupId);
        return mapper.findRecipients(groupId).stream().map(this::toRecipientResponse).toList();
    }

    /**
     * 创建加密通知接收目标。
     *
     * @param groupId 接收组标识
     * @param request 创建请求
     * @return 创建后的安全响应
     */
    @Transactional
    public NotificationRecipientResponse createRecipient(
            UUID groupId,
            NotificationRecipientRequest request
    ) {
        requireGroup(groupId);
        validateRecipientRequest(request);
        validateIdentity(request.recipientType(), request.userId());
        String normalizedTarget = normalizeTarget(request.channelType(), request.target(), true);
        String digest = cipher.blindIndex(
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, normalizedTarget);
        ensureUniqueTarget(groupId, request.channelType(), digest, null);
        UUID recipientId = idGenerator.next();
        String ciphertext = cipher.encrypt(
                recipientId, NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                0, normalizedTarget);
        Instant now = Instant.now();
        NotificationRecipient recipient = new NotificationRecipient(
                recipientId, groupId, request.recipientType(), request.userId(),
                request.displayName().strip(), request.channelType(), ciphertext, digest,
                maskTarget(request.channelType(), normalizedTarget), request.status(),
                now, now, 0);
        try {
            if (mapper.insertRecipient(recipient, currentOperatorId()) != 1) {
                throw new IllegalStateException("通知接收对象写入行数异常");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateTarget(groupId, request.channelType());
        }
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_RECIPIENT",
                "notification:recipient:edit", "NOTIFICATION_RECIPIENT",
                recipientId, Map.of(), recipientSummary(recipient));
        return toRecipientResponse(recipient);
    }

    /**
     * 使用乐观锁修改接收对象并将目标重新绑定到新版本。
     *
     * @param groupId 接收组标识
     * @param recipientId 接收对象标识
     * @param request 修改请求
     * @return 修改后的安全响应
     */
    @Transactional
    public NotificationRecipientResponse updateRecipient(
            UUID groupId,
            UUID recipientId,
            NotificationRecipientRequest request
    ) {
        requireGroup(groupId);
        NotificationRecipient existing = requireRecipient(groupId, recipientId);
        if (existing.version() != request.version()) {
            throw recipientConflict(recipientId);
        }
        validateRecipientRequest(request);
        validateIdentity(request.recipientType(), request.userId());
        String sourceTarget = request.target() == null || request.target().isBlank()
                ? decrypt(existing) : request.target();
        String normalizedTarget = normalizeTarget(request.channelType(), sourceTarget, true);
        String digest = cipher.blindIndex(
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, normalizedTarget);
        ensureUniqueTarget(groupId, request.channelType(), digest, recipientId);
        long nextVersion = existing.version() + 1;
        String ciphertext = cipher.encrypt(
                recipientId, NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                nextVersion, normalizedTarget);
        NotificationRecipient candidate = new NotificationRecipient(
                recipientId, groupId, request.recipientType(), request.userId(),
                request.displayName().strip(), request.channelType(), ciphertext, digest,
                maskTarget(request.channelType(), normalizedTarget), request.status(),
                existing.createdAt(), Instant.now(), nextVersion);
        try {
            if (mapper.updateRecipient(candidate, request.version(), currentOperatorId()) != 1) {
                throw recipientConflict(recipientId);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateTarget(groupId, request.channelType());
        }
        auditService.recordSuccess(
                "NOTIFICATION", "UPDATE_NOTIFICATION_RECIPIENT",
                "notification:recipient:edit", "NOTIFICATION_RECIPIENT",
                recipientId, recipientSummary(existing), recipientSummary(candidate));
        return toRecipientResponse(candidate);
    }

    /**
     * 显式解密真实接收目标并记录独立敏感读取审计。
     *
     * @param groupId 接收组标识
     * @param recipientId 接收对象标识
     * @return 明文接收目标
     */
    @Transactional
    public NotificationRecipientTargetResponse readTarget(UUID groupId, UUID recipientId) {
        NotificationRecipient recipient = requireRecipient(groupId, recipientId);
        String target = decrypt(recipient);
        auditService.recordSuccess(
                "NOTIFICATION", "READ_NOTIFICATION_RECIPIENT_TARGET",
                "notification:recipient:secret:view", "NOTIFICATION_RECIPIENT",
                recipientId, Map.of(), Map.of(
                        "channelType", recipient.channelType().name(),
                        "targetMasked", recipient.targetMasked(),
                        "result", "GRANTED"));
        return new NotificationRecipientTargetResponse(
                recipientId, target, recipient.version(), Instant.now());
    }

    private void validateGroupRequest(NotificationRecipientGroupRequest request) {
        if (request == null || request.groupCode() == null
                || !request.groupCode().matches("^[a-z][a-z0-9-]{1,63}$")
                || request.name() == null || request.name().isBlank()
                || request.name().length() > 128
                || !validStatus(request.status())) {
            throw invalid("INVALID_GROUP");
        }
        String description = normalizeNullable(request.description());
        if (description != null && description.length() > 500) {
            throw invalid("GROUP_DESCRIPTION_TOO_LONG");
        }
    }

    private void validateRecipientRequest(NotificationRecipientRequest request) {
        if (request == null || request.recipientType() == null
                || request.channelType() == null
                || request.displayName() == null || request.displayName().isBlank()
                || request.displayName().length() > 128
                || !validStatus(request.status())) {
            throw invalid("INVALID_RECIPIENT");
        }
    }

    private void validateIdentity(NotificationRecipientType type, UUID userId) {
        if (type == NotificationRecipientType.SYSTEM_USER) {
            if (userId == null || !mapper.systemUserEnabled(userId)) {
                throw invalid("SYSTEM_USER_NOT_ENABLED");
            }
            return;
        }
        if (userId != null) {
            throw invalid("EXTERNAL_RECIPIENT_MUST_NOT_REFERENCE_USER");
        }
    }

    private String normalizeTarget(
            NotificationChannelType channelType,
            String target,
            boolean required
    ) {
        if (target == null || target.isBlank()) {
            if (required) {
                throw invalid("TARGET_REQUIRED");
            }
            return null;
        }
        String stripped = target.strip();
        if (stripped.length() > MAX_TARGET_LENGTH || containsControlCharacters(stripped)) {
            throw invalid("TARGET_INVALID");
        }
        return switch (channelType) {
            case EMAIL -> normalizeEmail(stripped);
            case SMS, VOICE -> normalizePhone(stripped);
            case WEBHOOK -> normalizeWebhook(stripped);
            case ENTERPRISE_IM -> stripped;
        };
    }

    private String normalizeEmail(String target) {
        String normalized = target.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalized).matches() || normalized.length() > 320) {
            throw invalid("EMAIL_INVALID");
        }
        return normalized;
    }

    private String normalizePhone(String target) {
        String normalized = target.replaceAll("[\\s()\\-]", "");
        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2);
        }
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw invalid("PHONE_INVALID");
        }
        return normalized;
    }

    private String normalizeWebhook(String target) {
        URI uri;
        try {
            uri = URI.create(target).normalize();
        } catch (IllegalArgumentException exception) {
            throw invalid("WEBHOOK_INVALID");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw invalid("WEBHOOK_HTTPS_REQUIRED");
        }
        return uri.toASCIIString();
    }

    private String maskTarget(NotificationChannelType channelType, String target) {
        String masked = switch (channelType) {
            case EMAIL -> {
                int separator = target.indexOf('@');
                yield target.substring(0, 1) + "***" + target.substring(separator);
            }
            case SMS, VOICE -> "***" + target.substring(Math.max(0, target.length() - 4));
            case WEBHOOK -> maskWebhook(target);
            case ENTERPRISE_IM -> maskGeneric(target);
        };
        return masked.length() <= 256 ? masked : masked.substring(0, 253) + "...";
    }

    private String maskWebhook(String target) {
        URI uri = URI.create(target);
        int port = uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost()
                + (port < 0 ? "" : ":" + port) + "/***";
    }

    private String maskGeneric(String target) {
        if (target.length() <= 4) {
            return "***";
        }
        return target.substring(0, 2) + "***" + target.substring(target.length() - 2);
    }

    private boolean containsControlCharacters(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private void ensureUniqueTarget(
            UUID groupId,
            NotificationChannelType channelType,
            String digest,
            UUID excludedId
    ) {
        if (mapper.recipientTargetExists(groupId, channelType, digest, excludedId)) {
            throw duplicateTarget(groupId, channelType);
        }
    }

    private String decrypt(NotificationRecipient recipient) {
        return cipher.decrypt(
                recipient.id(), NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                recipient.version(), recipient.targetCiphertext());
    }

    private NotificationRecipientGroup requireGroup(UUID groupId) {
        return mapper.findGroupById(groupId).orElseThrow(() -> new MineException(
                GROUP_NOT_FOUND, Map.of("groupId", groupId)));
    }

    private NotificationRecipient requireRecipient(UUID groupId, UUID recipientId) {
        return mapper.findRecipientById(groupId, recipientId).orElseThrow(
                () -> new MineException(RECIPIENT_NOT_FOUND, Map.of(
                        "groupId", groupId, "recipientId", recipientId)));
    }

    private NotificationRecipientGroupResponse toGroupResponse(
            NotificationRecipientGroup group
    ) {
        return new NotificationRecipientGroupResponse(
                group.id(), group.groupCode(), group.name(), group.description(),
                group.status(), group.createdAt(), group.updatedAt(), group.version());
    }

    private NotificationRecipientResponse toRecipientResponse(NotificationRecipient recipient) {
        return new NotificationRecipientResponse(
                recipient.id(), recipient.groupId(), recipient.recipientType(),
                recipient.userId(), recipient.displayName(), recipient.channelType(),
                recipient.targetMasked(), recipient.targetCiphertext() != null,
                recipient.status(), recipient.createdAt(), recipient.updatedAt(),
                recipient.version());
    }

    private Map<String, Object> groupSummary(NotificationRecipientGroup group) {
        return Map.of(
                "groupCode", group.groupCode(),
                "name", group.name(),
                "status", group.status(),
                "version", group.version());
    }

    private Map<String, Object> recipientSummary(NotificationRecipient recipient) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("groupId", recipient.groupId());
        summary.put("recipientType", recipient.recipientType().name());
        if (recipient.userId() != null) {
            summary.put("userId", recipient.userId());
        }
        summary.put("channelType", recipient.channelType().name());
        summary.put("targetMasked", recipient.targetMasked());
        summary.put("status", recipient.status());
        summary.put("version", recipient.version());
        return Map.copyOf(summary);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private boolean validStatus(String status) {
        return status != null && STATUS_PATTERN.matcher(status).matches();
    }

    private MineException duplicateGroup(String groupCode) {
        return new MineException(GROUP_DUPLICATE, Map.of("groupCode", groupCode));
    }

    private MineException duplicateTarget(
            UUID groupId,
            NotificationChannelType channelType
    ) {
        return new MineException(TARGET_DUPLICATE, Map.of(
                "groupId", groupId, "channelType", String.valueOf(channelType)));
    }

    private MineException groupConflict(UUID groupId) {
        return new MineException(GROUP_CONFLICT, Map.of("groupId", groupId));
    }

    private MineException recipientConflict(UUID recipientId) {
        return new MineException(RECIPIENT_CONFLICT, Map.of("recipientId", recipientId));
    }

    private MineException invalid(String reason) {
        return new MineException(RECIPIENT_INVALID, Map.of("reason", reason));
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
