package com.bosco.mine.server.modules.mine.operation.domain;

import org.junit.jupiter.api.Test;

import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Action.CANCEL;
import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Action.CLOSE;
import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Action.OPEN;
import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Status.CANCELLED;
import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Status.CLOSED;
import static com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle.Status.PLANNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证班次生命周期只允许显式业务动作推进。
 */
class ShiftLifecycleTest {

    @Test
    void 计划班次应允许开启后关闭() {
        ShiftLifecycle.Status opened = ShiftLifecycle.transition(PLANNED, OPEN);

        assertThat(opened).isEqualTo(ShiftLifecycle.Status.OPEN);
        assertThat(ShiftLifecycle.transition(opened, CLOSE)).isEqualTo(CLOSED);
    }

    @Test
    void 计划班次应允许取消() {
        assertThat(ShiftLifecycle.transition(PLANNED, CANCEL)).isEqualTo(CANCELLED);
    }

    @Test
    void 已关闭班次不得重新开启() {
        assertThatThrownBy(() -> ShiftLifecycle.transition(CLOSED, OPEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED")
                .hasMessageContaining("OPEN");
    }

    @Test
    void 已开启班次不得取消() {
        assertThatThrownBy(() -> ShiftLifecycle.transition(ShiftLifecycle.Status.OPEN, CANCEL))
                .isInstanceOf(IllegalStateException.class);
    }
}
