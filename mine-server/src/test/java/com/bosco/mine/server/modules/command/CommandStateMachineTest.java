package com.bosco.mine.server.modules.command;

import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验命令状态机的全部合法边和禁止边。
 */
class CommandStateMachineTest {

    private static final Set<String> ALLOWED = Set.of(
            "CREATED->QUEUED",
            "CREATED->CANCELLED",
            "QUEUED->SENT",
            "QUEUED->EXPIRED",
            "QUEUED->CANCELLED",
            "SENT->DEVICE_RECEIVED",
            "SENT->TIMEOUT",
            "DEVICE_RECEIVED->SUCCEEDED",
            "DEVICE_RECEIVED->REFUSED",
            "DEVICE_RECEIVED->FAILED",
            "DEVICE_RECEIVED->TIMEOUT"
    );

    @Test
    void 应严格实现文档定义的状态迁移矩阵() {
        for (CommandStatus current : CommandStatus.values()) {
            for (CommandStatus target : CommandStatus.values()) {
                String edge = current + "->" + target;
                assertThat(current.canTransitionTo(target))
                        .as(edge)
                        .isEqualTo(ALLOWED.contains(edge));
                if (ALLOWED.contains(edge)) {
                    assertThat(current.requireTransitionTo(target)).isEqualTo(target);
                } else {
                    assertThatThrownBy(() -> current.requireTransitionTo(target))
                            .as(edge)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(current.name())
                            .hasMessageContaining(target.name());
                }
            }
        }
    }

    @Test
    void 应在迁移时固化对应时间点且不修改原实例() {
        Instant createdAt = Instant.parse("2026-07-15T08:00:00Z");
        CommandInstance created = CommandInstance.createLowRisk(
                UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f301"),
                UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f302"),
                "QUERY_STATUS",
                Map.of("includeLocation", true),
                "request-001",
                UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f303"),
                createdAt,
                300,
                30
        );

        CommandInstance queued = created.transitionTo(CommandStatus.QUEUED, createdAt.plusSeconds(1));

        assertThat(created.status()).isEqualTo(CommandStatus.CREATED);
        assertThat(created.queuedAt()).isNull();
        assertThat(queued.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(queued.queuedAt()).isEqualTo(createdAt.plusSeconds(1));
        assertThat(queued.expiresAt()).isEqualTo(createdAt.plusSeconds(300));
        assertThat(queued.parameters()).containsEntry("includeLocation", true);
    }
}
