package com.bosco.mine.server.modules.command.service;

import com.bosco.mine.common.error.MineException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验命令模板动态表单契约和服务端参数边界保持一致。
 */
class PublishedCommandTemplatesTest {

    @Test
    void 应发布状态查询上报周期和位置请求模板() {
        assertThat(PublishedCommandTemplates.publishedTemplates())
                .extracting(template -> template.key())
                .containsExactly("QUERY_STATUS", "SET_REPORT_INTERVAL", "REQUEST_LOCATION");
    }

    @Test
    void 应校验上报周期整数范围并拒绝额外字段() {
        PublishedCommandTemplates.validateParameters(
                "SET_REPORT_INTERVAL", Map.of("intervalSeconds", 60)
        );

        assertThatThrownBy(() -> PublishedCommandTemplates.validateParameters(
                "SET_REPORT_INTERVAL", Map.of("intervalSeconds", 10)
        )).isInstanceOf(MineException.class);
        assertThatThrownBy(() -> PublishedCommandTemplates.validateParameters(
                "SET_REPORT_INTERVAL", Map.of("intervalSeconds", 60, "raw", "unsafe")
        )).isInstanceOf(MineException.class);
    }
}
