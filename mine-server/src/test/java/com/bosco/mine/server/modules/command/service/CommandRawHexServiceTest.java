package com.bosco.mine.server.modules.command.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.command.outbox.OutboxMapper;
import com.bosco.mine.server.modules.command.repository.CommandMapper;
import com.bosco.mine.server.modules.command.repository.StoredCommand;
import com.bosco.mine.server.modules.command.template.CommandTemplateResponse;
import com.bosco.mine.server.modules.command.template.CommandTemplateService;
import com.bosco.mine.server.modules.device.dto.DeviceAccessStatusResponse;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.service.DeviceAccessStatusService;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验临时十六进制命令的在线限制、执行快照和脱敏 Outbox 载荷。
 */
class CommandRawHexServiceTest {
    private static final UUID USER_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425701");
    private static final UUID DEVICE_ID = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425702");

    @Test
    void 在线Mqtt设备应固化规范化原始字节并写入Outbox() {
        CommandMapper commandMapper = mock(CommandMapper.class);
        OutboxMapper outboxMapper = mock(OutboxMapper.class);
        OutboxCommitNotifier notifier = mock(OutboxCommitNotifier.class);
        OperationAuditService auditService = mock(OperationAuditService.class);
        DeviceConnectionProfileMapper profileMapper = mock(DeviceConnectionProfileMapper.class);
        DeviceAccessStatusService statusService = mock(DeviceAccessStatusService.class);
        DeviceConnectionProfile profile = mock(DeviceConnectionProfile.class);
        when(profile.connectionMode()).thenReturn("DEVICE_TO_PLATFORM_MQTT");
        when(profileMapper.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(profile));
        when(statusService.find(java.util.List.of(DEVICE_ID))).thenReturn(java.util.List.of(
                new DeviceAccessStatusResponse(DEVICE_ID, "ONLINE", null, null, 60, "ACCESS_ONLINE")
        ));
        when(commandMapper.findByIdempotencyKey(USER_ID, "raw-request-1")).thenReturn(Optional.empty());
        when(commandMapper.lockDeviceStatus(DEVICE_ID)).thenReturn(Optional.of("ENABLED"));
        AtomicReference<CommandInstance> inserted = new AtomicReference<>();
        when(commandMapper.insert(any(), anyString(), eq("AD_HOC_HEX"), eq("RAW_HEX"),
                any(byte[].class), anyString(), anyString(), eq(false)))
                .thenAnswer(invocation -> {
                    inserted.set(invocation.getArgument(0));
                    return 1;
                });
        when(commandMapper.findById(any())).thenAnswer(invocation -> Optional.of(stored(inserted.get())));
        CommandService service = new CommandService(
                commandMapper, outboxMapper, notifier, auditService,
                mock(CommandTemplateService.class), profileMapper, statusService
        );

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(USER_ID.toString());

            var response = service.createCurrentUserRawHexCommand(
                    DEVICE_ID, "0xAA bb 01", "现场协议联调",
                    "raw-request-1", 300, "trace-raw-1"
            );

            assertThat(response.dispatchSource()).isEqualTo("AD_HOC_HEX");
            assertThat(response.rawPayloadHex()).isEqualTo("AABB01");
        }

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(commandMapper).insert(any(), eq("{}"), eq("AD_HOC_HEX"), eq("RAW_HEX"),
                bytes.capture(), eq("AABB01"), anyString(), eq(false));
        assertThat(bytes.getValue()).containsExactly((byte) 0xAA, (byte) 0xBB, (byte) 0x01);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insertCommandDispatch(any(), any(), payload.capture(), anyString());
        assertThat(payload.getValue()).contains("\"payloadFormat\":\"RAW_HEX\"")
                .contains("\"rawPayloadBase64\":\"qrsB\"")
                .contains("\"replyExpected\":false");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSuccess(
                eq("COMMAND_CENTER"), eq("SUBMIT_RAW_HEX_COMMAND"), eq("command:raw:execute"),
                eq("COMMAND"), any(), eq(Map.of()), audit.capture()
        );
        assertThat(audit.getValue())
                .containsEntry("reason", "现场协议联调")
                .containsEntry("byteLength", 3)
                .containsKey("payloadSha256")
                .doesNotContainKeys("rawPayload", "rawPayloadHex");
    }

    @Test
    void 固定十六进制模板不得向非Mqtt设备下发() {
        CommandMapper commandMapper = mock(CommandMapper.class);
        CommandTemplateService templateService = mock(CommandTemplateService.class);
        DeviceConnectionProfileMapper profileMapper = mock(DeviceConnectionProfileMapper.class);
        DeviceConnectionProfile profile = mock(DeviceConnectionProfile.class);
        when(profile.connectionMode()).thenReturn("PLATFORM_TO_DEVICE_TCP");
        when(profileMapper.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(profile));
        when(templateService.requirePublishedForDevice(DEVICE_ID, "HORN_RAW"))
                .thenReturn(rawTemplate());
        CommandService service = new CommandService(
                commandMapper, mock(OutboxMapper.class), mock(OutboxCommitNotifier.class),
                mock(OperationAuditService.class), templateService, profileMapper,
                mock(DeviceAccessStatusService.class)
        );

        assertThatThrownBy(() -> service.createLowRiskCommand(
                USER_ID, DEVICE_ID, "HORN_RAW", Map.of(), "固定模板联调", null,
                "fixed-raw-request-1", 300, 30, "trace-fixed-raw-1"
        )).hasMessageContaining("MQTT");
        verify(commandMapper, org.mockito.Mockito.never()).insert(
                any(), anyString(), anyString(), anyString(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    void 临时十六进制精确幂等重试不应因设备已经离线而失败() {
        CommandMapper commandMapper = mock(CommandMapper.class);
        DeviceConnectionProfileMapper profileMapper = mock(DeviceConnectionProfileMapper.class);
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        CommandInstance existing = CommandInstance.createLowRisk(
                UUID.randomUUID(), DEVICE_ID, "RAW_HEX_AD_HOC", 1, Map.of(),
                "现场协议联调", null, "raw-request-retry", USER_ID, now, 300, 1
        ).transitionTo(CommandStatus.QUEUED, now);
        StoredCommand stored = stored(existing);
        when(commandMapper.findByIdempotencyKey(USER_ID, "raw-request-retry"))
                .thenReturn(Optional.of(stored));
        when(commandMapper.findById(existing.id())).thenReturn(Optional.of(stored));
        CommandService service = new CommandService(
                commandMapper, mock(OutboxMapper.class), mock(OutboxCommitNotifier.class),
                mock(OperationAuditService.class), mock(CommandTemplateService.class),
                profileMapper, mock(DeviceAccessStatusService.class)
        );

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn(USER_ID.toString());

            var response = service.createCurrentUserRawHexCommand(
                    DEVICE_ID, "AA BB 01", "现场协议联调",
                    "raw-request-retry", 300, "trace-raw-retry"
            );

            assertThat(response.id()).isEqualTo(existing.id());
        }
        verify(profileMapper, org.mockito.Mockito.never()).findByDeviceId(any());
    }

    private CommandTemplateResponse rawTemplate() {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new CommandTemplateResponse(
                UUID.randomUUID(), UUID.randomUUID(), "HORN_RAW", 1, "PUBLISHED",
                "鸣笛原始命令", null, "测试模板", "LOW", "RAW_HEX", Map.of(
                        "type", "object", "additionalProperties", false, "properties", Map.of()
                ), "AA", "a".repeat(64), 300, 30, true, false,
                java.util.List.of(UUID.randomUUID()), now, now, now, 1
        );
    }

    private StoredCommand stored(CommandInstance command) {
        Instant now = command.createdAt();
        return new StoredCommand(
                command.id(), command.deviceId(), command.templateKey(), command.templateVersion(),
                "{}", command.reason(), command.workOrderNo(), null, null,
                "AD_HOC_HEX", "RAW_HEX", "AABB01",
                "c5f30c9496ad85fbb4c29dec037731a05aeb7d0ce2cde2916005bdd0f7156b1f",
                false, "LOW", "QUEUED", 300, 1, command.idempotencyKey(), null, null,
                USER_ID, now, now, now, null, null, null, command.expiresAt(), null, null, false, 1
        );
    }
}
