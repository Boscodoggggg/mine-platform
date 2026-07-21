package com.bosco.mine.server.modules.command.status;

import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.bosco.mine.server.modules.command.status.CommandStatusRepository.CommandStateUpdate;
import com.bosco.mine.server.modules.command.status.CommandStatusRepository.CommandStatusSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验无需业务回复的原始命令以 Broker 发布确认作为最终状态。
 */
class CommandStatusServiceTest {

    @Test
    void 排队命令收到PUBLISHED事件后应同时记录发送和完成时间() {
        UUID eventId = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425501");
        UUID commandId = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425502");
        UUID deviceId = UUID.fromString("019f82c7-99a0-7472-bdc8-327702425503");
        Instant occurredAt = Instant.parse("2026-07-21T04:30:00Z");
        CommandStatusRepository repository = mock(CommandStatusRepository.class);
        when(repository.recordInbox(eventId)).thenReturn(true);
        when(repository.lockById(commandId)).thenReturn(Optional.of(
                new CommandStatusSnapshot(deviceId, "RAW_HEX_AD_HOC", "QUEUED", null, null, null)
        ));
        CommandStatusService service = new CommandStatusService(repository);

        boolean changed = service.apply(eventId, new CommandStatusPayload(
                commandId, deviceId, "PUBLISHED", occurredAt, null, Map.of(), null
        ));

        ArgumentCaptor<CommandStateUpdate> captor = ArgumentCaptor.forClass(CommandStateUpdate.class);
        verify(repository).update(org.mockito.ArgumentMatchers.eq(commandId), captor.capture());
        assertThat(changed).isTrue();
        assertThat(captor.getValue().status()).isEqualTo("PUBLISHED");
        assertThat(captor.getValue().sentAt()).isEqualTo(occurredAt);
        assertThat(captor.getValue().completedAt()).isEqualTo(occurredAt);
    }
}
