package com.bosco.mine.server.modules.notification.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientTargetResponse;
import com.bosco.mine.server.modules.notification.model.NotificationRecipient;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientGroup;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientType;
import com.bosco.mine.server.modules.notification.repository.NotificationRecipientMapper;
import com.bosco.mine.server.modules.notification.security.NotificationSecretCipher;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通知接收组、接收目标加密和敏感读取边界。
 */
class NotificationRecipientServiceTest {

    private NotificationRecipientMapper mapper;
    private NotificationSecretCipher cipher;
    private OperationAuditService auditService;
    private NotificationRecipientService service;

    @BeforeEach
    void 创建接收对象测试环境() {
        mapper = mock(NotificationRecipientMapper.class);
        cipher = mock(NotificationSecretCipher.class);
        auditService = mock(OperationAuditService.class);
        service = new NotificationRecipientService(mapper, cipher, auditService);
    }

    @Test
    void 创建外部邮件接收目标必须标准化加密并仅返回脱敏值() {
        UUID groupId = UUID.randomUUID();
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(groupId, 0)));
        when(cipher.blindIndex(
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                "operator@example.com"
        )).thenReturn("sha256:" + "a".repeat(64));
        when(cipher.encrypt(any(),
                eq(NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET),
                eq(0L), eq("operator@example.com")))
                .thenReturn("v1:nonce:ciphertext");
        when(mapper.insertRecipient(any(), any())).thenReturn(1);

        NotificationRecipientResponse response = service.createRecipient(
                groupId,
                recipientRequest(NotificationRecipientType.EXTERNAL_CONTACT, null,
                        NotificationChannelType.EMAIL, " Operator@Example.COM ", 0));

        ArgumentCaptor<NotificationRecipient> recipient =
                ArgumentCaptor.forClass(NotificationRecipient.class);
        verify(mapper).insertRecipient(recipient.capture(), any());
        assertThat(recipient.getValue().targetCiphertext()).isEqualTo("v1:nonce:ciphertext");
        assertThat(recipient.getValue().targetDigest()).startsWith("sha256:");
        assertThat(recipient.getValue().targetMasked()).isEqualTo("o***@example.com");
        assertThat(response.targetMasked()).isEqualTo("o***@example.com");
        assertThat(response.toString())
                .doesNotContain("operator@example.com", "v1:nonce:ciphertext", "sha256:");
    }

    @Test
    void 更新非目标字段也必须把旧目标重新绑定到新版本() {
        UUID groupId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        NotificationRecipient existing = recipient(
                recipientId, groupId, "v1:old:ciphertext", 4);
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(groupId, 0)));
        when(mapper.findRecipientById(groupId, recipientId)).thenReturn(Optional.of(existing));
        when(cipher.decrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                4, "v1:old:ciphertext")).thenReturn("13800138000");
        when(cipher.blindIndex(
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                "13800138000")).thenReturn("sha256:" + "b".repeat(64));
        when(cipher.encrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                5, "13800138000")).thenReturn("v1:new:ciphertext");
        when(mapper.updateRecipient(any(), eq(4L), any())).thenReturn(1);

        NotificationRecipientResponse response = service.updateRecipient(
                groupId, recipientId,
                recipientRequest(NotificationRecipientType.EXTERNAL_CONTACT, null,
                        NotificationChannelType.SMS, null, 4));

        ArgumentCaptor<NotificationRecipient> recipient =
                ArgumentCaptor.forClass(NotificationRecipient.class);
        verify(mapper).updateRecipient(recipient.capture(), eq(4L), any());
        assertThat(recipient.getValue().targetCiphertext()).isEqualTo("v1:new:ciphertext");
        assertThat(recipient.getValue().version()).isEqualTo(5);
        assertThat(response.version()).isEqualTo(5);
    }

    @Test
    void 系统用户接收人必须引用可用用户且外部联系人不得携带用户标识() {
        UUID groupId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(groupId, 0)));
        when(mapper.systemUserEnabled(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.createRecipient(
                groupId,
                recipientRequest(NotificationRecipientType.SYSTEM_USER, userId,
                        NotificationChannelType.SMS, "13800138000", 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "SYSTEM_USER_NOT_ENABLED"));
        assertThatThrownBy(() -> service.createRecipient(
                groupId,
                recipientRequest(NotificationRecipientType.EXTERNAL_CONTACT, userId,
                        NotificationChannelType.SMS, "13800138000", 0)))
                .isInstanceOf(MineException.class)
                .satisfies(exception -> assertThat(((MineException) exception).details())
                        .containsEntry("reason", "EXTERNAL_RECIPIENT_MUST_NOT_REFERENCE_USER"));
    }

    @Test
    void 组内相同渠道目标必须按盲索引阻止重复() {
        UUID groupId = UUID.randomUUID();
        String digest = "sha256:" + "c".repeat(64);
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(groupId, 0)));
        when(cipher.blindIndex(
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                "13800138000")).thenReturn(digest);
        when(mapper.recipientTargetExists(
                groupId, NotificationChannelType.SMS, digest, null)).thenReturn(true);

        assertThatThrownBy(() -> service.createRecipient(
                groupId,
                recipientRequest(NotificationRecipientType.EXTERNAL_CONTACT, null,
                        NotificationChannelType.SMS, "138 0013 8000", 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("已经存在");
    }

    @Test
    void 显式读取真实目标必须解密且审计中不得包含明文() {
        UUID groupId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(mapper.findRecipientById(groupId, recipientId)).thenReturn(Optional.of(
                recipient(recipientId, groupId, "v1:nonce:ciphertext", 2)));
        when(cipher.decrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET,
                2, "v1:nonce:ciphertext")).thenReturn("13800138000");

        NotificationRecipientTargetResponse response =
                service.readTarget(groupId, recipientId);

        assertThat(response.target()).isEqualTo("13800138000");
        assertThat(response.toString()).doesNotContain("13800138000");
        ArgumentCaptor<Map<String, Object>> auditAfter = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSuccess(
                eq("NOTIFICATION"),
                eq("READ_NOTIFICATION_RECIPIENT_TARGET"),
                eq("notification:recipient:secret:view"),
                eq("NOTIFICATION_RECIPIENT"),
                eq(recipientId),
                eq(Map.of()),
                auditAfter.capture());
        assertThat(auditAfter.getValue().toString()).doesNotContain("13800138000");
    }

    @Test
    void 接收组必须校验编码唯一和乐观锁() {
        when(mapper.findGroupByCode("on-duty")).thenReturn(Optional.of(group(
                UUID.randomUUID(), 0)));
        assertThatThrownBy(() -> service.createGroup(groupRequest("on-duty", 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("编码");

        UUID groupId = UUID.randomUUID();
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(groupId, 3)));
        assertThatThrownBy(() -> service.updateGroup(
                groupId, groupRequest("on-duty", 2)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("刷新");
    }

    private NotificationRecipientGroupRequest groupRequest(String code, long version) {
        return new NotificationRecipientGroupRequest(
                code, "值班人员", "严重告警值班组", "ENABLED", version);
    }

    private NotificationRecipientRequest recipientRequest(
            NotificationRecipientType recipientType,
            UUID userId,
            NotificationChannelType channelType,
            String target,
            long version
    ) {
        return new NotificationRecipientRequest(
                recipientType, userId, "值班负责人", channelType, target, "ENABLED", version);
    }

    private NotificationRecipientGroup group(UUID id, long version) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationRecipientGroup(
                id, "on-duty", "值班人员", "严重告警值班组",
                "ENABLED", now, now, version);
    }

    private NotificationRecipient recipient(
            UUID id,
            UUID groupId,
            String ciphertext,
            long version
    ) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationRecipient(
                id, groupId, NotificationRecipientType.EXTERNAL_CONTACT, null,
                "值班负责人", NotificationChannelType.SMS, ciphertext,
                "sha256:" + "d".repeat(64), "***8000", "ENABLED",
                now, now, version);
    }
}
