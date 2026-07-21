package com.bosco.mine.server.modules.notification.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelResponse;
import com.bosco.mine.server.modules.notification.model.NotificationChannel;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderRegistry;
import com.bosco.mine.server.modules.notification.repository.NotificationChannelMapper;
import com.bosco.mine.server.modules.notification.security.NotificationSecretCipher;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理平台内置通知 Provider 的数据库渠道配置和加密凭据。
 */
@Service
public class NotificationChannelService {

    private static final ErrorCode CHANNEL_NOT_FOUND =
            new ErrorCode("NOTIFICATION-404-001", "通知渠道不存在");
    private static final ErrorCode CHANNEL_CONFLICT =
            new ErrorCode("NOTIFICATION-409-001", "数据已被其他管理员修改，请刷新后重试");
    private static final ErrorCode CHANNEL_DUPLICATE =
            new ErrorCode("NOTIFICATION-409-002", "通知渠道编码已经存在");
    private static final ErrorCode PROVIDER_UNSUPPORTED =
            new ErrorCode("NOTIFICATION-400-001", "通知供应商或渠道类型不受平台支持");
    private static final ErrorCode PROVIDER_CONFIGURATION_INVALID =
            new ErrorCode("NOTIFICATION-400-002", "通知供应商配置不合法");

    private final NotificationChannelMapper mapper;
    private final NotificationProviderRegistry registry;
    private final NotificationSecretCipher cipher;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建通知渠道管理服务。
     *
     * @param mapper 通知渠道数据接口
     * @param registry 内置 Provider 注册表
     * @param cipher 通知独立密钥加密器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public NotificationChannelService(
            NotificationChannelMapper mapper,
            NotificationProviderRegistry registry,
            NotificationSecretCipher cipher,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.registry = registry;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询平台随版本发布的 Provider 目录。
     *
     * @return Provider 字段元数据
     */
    public List<NotificationProviderDefinition> providers() {
        return registry.catalog();
    }

    /**
     * 查询全部通知渠道，不解密凭据。
     *
     * @return 通知渠道响应
     */
    @Transactional(readOnly = true)
    public List<NotificationChannelResponse> list() {
        return mapper.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * 查询指定通知渠道，不解密凭据。
     *
     * @param id 渠道标识
     * @return 通知渠道响应
     */
    @Transactional(readOnly = true)
    public NotificationChannelResponse get(UUID id) {
        return toResponse(requireChannel(id));
    }

    /**
     * 创建数据库通知渠道配置。
     *
     * @param request 创建请求
     * @return 创建后的安全响应
     */
    @Transactional
    public NotificationChannelResponse create(NotificationChannelRequest request) {
        if (mapper.findByCode(request.channelCode()).isPresent()) {
            throw duplicate(request.channelCode());
        }
        NotificationAdapterKey key = key(request);
        Map<String, Object> configuration = normalizedConfiguration(request.configuration());
        Map<String, String> credentials = normalizedSecrets(request.secrets());
        validate(key, configuration, credentials, request.status());
        UUID id = idGenerator.next();
        long version = 0;
        Instant now = Instant.now();
        String secretCiphertext = encrypt(id, version, credentials);
        NotificationChannel channel = new NotificationChannel(
                id, request.channelCode(), request.name().strip(), request.channelType(),
                request.provider(), request.status(), writeJson(configuration), secretCiphertext,
                request.connectTimeoutMs(), request.readTimeoutMs(), null, now, now, version);
        try {
            if (mapper.insert(channel, currentOperatorId()) != 1) {
                throw new IllegalStateException("通知渠道写入行数异常");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicate(request.channelCode());
        }
        NotificationChannel saved = mapper.findByCode(request.channelCode()).orElse(channel);
        auditService.recordSuccess(
                "NOTIFICATION", "CREATE_NOTIFICATION_CHANNEL", "notification:channel:edit",
                "NOTIFICATION_CHANNEL", saved.id(), Map.of(), summary(saved, configuration));
        return toResponse(saved);
    }

    /**
     * 使用乐观锁修改渠道，并将全部凭据重新绑定到新版本。
     *
     * @param id 渠道标识
     * @param request 修改请求
     * @return 修改后的安全响应
     */
    @Transactional
    public NotificationChannelResponse update(UUID id, NotificationChannelRequest request) {
        NotificationChannel existing = requireChannel(id);
        if (!existing.channelCode().equals(request.channelCode())) {
            throw invalidConfiguration(request.provider(), "channelCode");
        }
        if (existing.version() != request.version()) {
            throw conflict(id);
        }
        NotificationAdapterKey key = key(request);
        Map<String, Object> configuration = normalizedConfiguration(request.configuration());
        boolean sameProvider = existing.channelType() == request.channelType()
                && existing.provider().equals(request.provider());
        Map<String, String> credentials = sameProvider
                ? decrypt(existing) : new LinkedHashMap<>();
        mergeSecrets(credentials, request.secrets());
        validate(key, configuration, credentials, request.status());
        long nextVersion = existing.version() + 1;
        String secretCiphertext = encrypt(id, nextVersion, credentials);
        NotificationChannel candidate = new NotificationChannel(
                id, existing.channelCode(), request.name().strip(), request.channelType(),
                request.provider(), request.status(), writeJson(configuration), secretCiphertext,
                request.connectTimeoutMs(), request.readTimeoutMs(), null,
                existing.createdAt(), Instant.now(), nextVersion);
        if (mapper.update(candidate, request.version(), currentOperatorId()) != 1) {
            throw conflict(id);
        }
        NotificationChannel saved = mapper.findById(id).orElse(candidate);
        auditService.recordSuccess(
                "NOTIFICATION", "UPDATE_NOTIFICATION_CHANNEL", "notification:channel:edit",
                "NOTIFICATION_CHANNEL", id,
                summary(existing, readConfiguration(existing)), summary(saved, configuration));
        return toResponse(saved);
    }

    private NotificationAdapterKey key(NotificationChannelRequest request) {
        try {
            NotificationAdapterKey key = new NotificationAdapterKey(
                    request.channelType(), request.provider());
            registry.require(key);
            return key;
        } catch (IllegalArgumentException exception) {
            throw new MineException(PROVIDER_UNSUPPORTED, Map.of(
                    "channelType", String.valueOf(request.channelType()),
                    "provider", safeProvider(request.provider())));
        }
    }

    private void validate(
            NotificationAdapterKey key,
            Map<String, Object> configuration,
            Map<String, String> credentials,
            String status
    ) {
        try {
            registry.validate(key, configuration, credentials, "ENABLED".equals(status));
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration(key.provider(), safeMessage(exception));
        }
    }

    private Map<String, Object> normalizedConfiguration(Map<String, Object> source) {
        return new LinkedHashMap<>(Optional.ofNullable(source).orElse(Map.of()));
    }

    private Map<String, String> normalizedSecrets(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        mergeSecrets(result, source);
        return result;
    }

    private void mergeSecrets(Map<String, String> target, Map<String, String> changes) {
        Optional.ofNullable(changes).orElse(Map.of()).forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                target.remove(key);
            } else {
                target.put(key, value);
            }
        });
    }

    private String encrypt(UUID id, long version, Map<String, String> credentials) {
        if (credentials.isEmpty()) {
            return null;
        }
        return cipher.encrypt(id, NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                version, writeJson(credentials));
    }

    private Map<String, String> decrypt(NotificationChannel channel) {
        if (channel.secretCiphertext() == null || channel.secretCiphertext().isBlank()) {
            return new LinkedHashMap<>();
        }
        String plaintext = cipher.decrypt(
                channel.id(), NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                channel.version(), channel.secretCiphertext());
        try {
            return new LinkedHashMap<>(objectMapper.readValue(plaintext, new TypeReference<>() { }));
        } catch (JacksonException exception) {
            throw new IllegalStateException("通知渠道凭据结构无效", exception);
        }
    }

    private NotificationChannel requireChannel(UUID id) {
        return mapper.findById(id).orElseThrow(() -> new MineException(
                CHANNEL_NOT_FOUND, Map.of("channelId", id)));
    }

    private NotificationChannelResponse toResponse(NotificationChannel channel) {
        return new NotificationChannelResponse(
                channel.id(), channel.channelCode(), channel.name(), channel.channelType(),
                channel.provider(), channel.status(), Map.copyOf(readConfiguration(channel)),
                channel.secretCiphertext() != null && !channel.secretCiphertext().isBlank(),
                channel.connectTimeoutMs(), channel.readTimeoutMs(), channel.lastVerifiedAt(),
                channel.createdAt(), channel.updatedAt(), channel.version());
    }

    private Map<String, Object> readConfiguration(NotificationChannel channel) {
        try {
            return objectMapper.readValue(channel.configurationJson(), new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("通知渠道配置结构无效", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("通知渠道配置序列化失败", exception);
        }
    }

    private Map<String, Object> summary(
            NotificationChannel channel,
            Map<String, Object> configuration
    ) {
        return Map.of(
                "channelCode", channel.channelCode(),
                "channelType", channel.channelType().name(),
                "provider", channel.provider(),
                "status", channel.status(),
                "configurationKeys", configuration.keySet().stream().sorted().toList(),
                "secretConfigured", channel.secretCiphertext() != null,
                "version", channel.version());
    }

    private MineException conflict(UUID id) {
        return new MineException(CHANNEL_CONFLICT, Map.of("channelId", id));
    }

    private MineException duplicate(String code) {
        return new MineException(CHANNEL_DUPLICATE, Map.of("channelCode", code));
    }

    private MineException invalidConfiguration(String provider, String reason) {
        return new MineException(PROVIDER_CONFIGURATION_INVALID, Map.of(
                "provider", safeProvider(provider), "reason", reason));
    }

    private String safeProvider(String provider) {
        return provider == null || !provider.matches("^[a-z][a-z0-9-]{1,63}$")
                ? "INVALID" : provider;
    }

    private String safeMessage(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return message == null || message.length() > 256 ? "INVALID_CONFIGURATION" : message;
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
