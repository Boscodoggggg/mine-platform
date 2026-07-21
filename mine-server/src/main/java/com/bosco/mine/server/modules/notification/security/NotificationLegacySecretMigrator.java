package com.bosco.mine.server.modules.notification.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在应用就绪前把历史通用 Webhook 明文地址迁入渠道凭据密文。
 */
@Component
public class NotificationLegacySecretMigrator {

    private static final String ENDPOINT_FIELD = "endpointUrl";
    private static final int MAX_ENDPOINT_LENGTH = 2048;

    private final NotificationSecretKeyGuardMapper mapper;
    private final NotificationSecretCipher cipher;
    private final ObjectMapper objectMapper;

    /**
     * 创建通知历史敏感字段迁移器。
     *
     * @param mapper 通知密钥门禁数据接口
     * @param cipher 通知敏感数据加密器
     * @param objectMapper JSON 映射器
     */
    public NotificationLegacySecretMigrator(
            NotificationSecretKeyGuardMapper mapper,
            NotificationSecretCipher cipher,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /**
     * 分批迁移全部历史通用 Webhook 地址，任一记录无法安全迁移时阻断启动。
     */
    @Transactional
    public void migrate() {
        while (true) {
            List<NotificationSecretKeyGuardMapper.LegacyWebhookChannel> channels =
                    mapper.findLegacyWebhookChannels();
            if (channels.isEmpty()) {
                return;
            }
            channels.forEach(this::migrateChannel);
        }
    }

    private void migrateChannel(
            NotificationSecretKeyGuardMapper.LegacyWebhookChannel channel
    ) {
        Map<String, Object> configuration = readConfiguration(channel.configurationJson());
        Object rawEndpoint = configuration.remove(ENDPOINT_FIELD);
        if (!(rawEndpoint instanceof String endpoint)
                || endpoint.isBlank()
                || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalStateException("历史 Webhook 端点无法安全迁移");
        }

        Map<String, String> credentials = readCredentials(channel);
        credentials.values().removeIf(java.util.Objects::isNull);
        credentials.putIfAbsent(ENDPOINT_FIELD, endpoint);
        long nextVersion = channel.version() + 1;
        String secretCiphertext = cipher.encrypt(
                channel.id(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                nextVersion,
                writeJson(credentials)
        );
        if (mapper.migrateLegacyWebhookChannel(
                channel.id(),
                channel.version(),
                writeJson(configuration),
                secretCiphertext
        ) != 1) {
            throw new IllegalStateException("历史 Webhook 端点迁移发生并发冲突");
        }
    }

    private Map<String, Object> readConfiguration(String json) {
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<>() { }));
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalStateException("历史 Webhook 配置结构无效", exception);
        }
    }

    private Map<String, String> readCredentials(
            NotificationSecretKeyGuardMapper.LegacyWebhookChannel channel
    ) {
        if (channel.secretCiphertext() == null || channel.secretCiphertext().isBlank()) {
            return new LinkedHashMap<>();
        }
        String plaintext = cipher.decrypt(
                channel.id(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                channel.version(),
                channel.secretCiphertext()
        );
        try {
            return new LinkedHashMap<>(objectMapper.readValue(
                    plaintext, new TypeReference<>() { }));
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalStateException("历史 Webhook 凭据结构无效", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("历史 Webhook 配置无法序列化", exception);
        }
    }
}
