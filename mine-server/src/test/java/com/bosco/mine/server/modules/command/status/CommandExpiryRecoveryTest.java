package com.bosco.mine.server.modules.command.status;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验管理服务启动时会修复历史遗留的过期排队命令。
 */
class CommandExpiryRecoveryTest {

    @Test
    void 启动时应将已超过TTL的排队命令修复为过期() {
        Instant now = Instant.parse("2026-07-21T08:00:00Z");
        CommandStatusRepository repository = mock(CommandStatusRepository.class);
        when(repository.expireQueuedCommands(now)).thenReturn(2);
        CommandExpiryRecovery recovery = new CommandExpiryRecovery(
                repository, Clock.fixed(now, ZoneOffset.UTC)
        );

        int recovered = recovery.recover();

        assertThat(recovered).isEqualTo(2);
        verify(repository).expireQueuedCommands(now);
    }
}
