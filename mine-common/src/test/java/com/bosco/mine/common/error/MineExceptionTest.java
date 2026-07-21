package com.bosco.mine.common.error;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验平台异常的稳定错误信息和详情隔离行为。
 */
class MineExceptionTest {

    @Test
    void 应保留稳定错误码消息和详情() {
        ErrorCode errorCode = new ErrorCode("DEVICE_NOT_FOUND", "设备不存在");
        MineException exception = new MineException(errorCode, Map.of("deviceId", "truck-001"));

        assertThat(exception.errorCode()).isEqualTo(errorCode);
        assertThat(exception.getMessage()).isEqualTo("设备不存在");
        assertThat(exception.details()).containsEntry("deviceId", "truck-001");
    }

    @Test
    void 应防止调用方修改异常详情() {
        Map<String, Object> details = new HashMap<>();
        details.put("deviceId", "truck-001");

        MineException exception = new MineException(
                new ErrorCode("DEVICE_NOT_FOUND", "设备不存在"), details);
        details.put("deviceId", "truck-002");

        assertThat(exception.details()).containsEntry("deviceId", "truck-001");
        assertThatThrownBy(() -> exception.details().put("deviceId", "truck-003"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 应拒绝空白错误码() {
        assertThatThrownBy(() -> new ErrorCode(" ", "参数不合法"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("错误码");
    }
}
