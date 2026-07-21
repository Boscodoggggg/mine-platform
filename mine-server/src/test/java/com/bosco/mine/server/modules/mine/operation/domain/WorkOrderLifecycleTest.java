package com.bosco.mine.server.modules.mine.operation.domain;

import org.junit.jupiter.api.Test;

import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.ABORT;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.CANCEL;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.COMPLETE;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.PAUSE;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.RELEASE;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.RESUME;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Action.START;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.ABORTED;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.CANCELLED;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.COMPLETED;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.DRAFT;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.PAUSED;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.RELEASED;
import static com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle.Status.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证作业单生命周期的允许迁移和终态保护。
 */
class WorkOrderLifecycleTest {

    @Test
    void 作业单应从草稿发布并启动() {
        assertThat(WorkOrderLifecycle.transition(DRAFT, RELEASE)).isEqualTo(RELEASED);
        assertThat(WorkOrderLifecycle.transition(RELEASED, START)).isEqualTo(RUNNING);
    }

    @Test
    void 运行作业应允许暂停恢复和完成() {
        assertThat(WorkOrderLifecycle.transition(RUNNING, PAUSE)).isEqualTo(PAUSED);
        assertThat(WorkOrderLifecycle.transition(PAUSED, RESUME)).isEqualTo(RUNNING);
        assertThat(WorkOrderLifecycle.transition(RUNNING, COMPLETE)).isEqualTo(COMPLETED);
        assertThat(WorkOrderLifecycle.transition(PAUSED, COMPLETE)).isEqualTo(COMPLETED);
    }

    @Test
    void 运行或暂停作业应允许异常终止() {
        assertThat(WorkOrderLifecycle.transition(RUNNING, ABORT)).isEqualTo(ABORTED);
        assertThat(WorkOrderLifecycle.transition(PAUSED, ABORT)).isEqualTo(ABORTED);
    }

    @Test
    void 未运行作业应允许取消() {
        assertThat(WorkOrderLifecycle.transition(DRAFT, CANCEL)).isEqualTo(CANCELLED);
        assertThat(WorkOrderLifecycle.transition(RELEASED, CANCEL)).isEqualTo(CANCELLED);
    }

    @Test
    void 完成作业不得再次暂停() {
        assertThatThrownBy(() -> WorkOrderLifecycle.transition(COMPLETED, PAUSE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("PAUSE");
    }

    @Test
    void 草稿作业不得直接启动() {
        assertThatThrownBy(() -> WorkOrderLifecycle.transition(DRAFT, START))
                .isInstanceOf(IllegalStateException.class);
    }
}
