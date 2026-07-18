package com.bosco.mine.simulator.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证管理页中看起来可操作的区域具有明确交互或阻塞说明。
 */
class StaticInteractionContractTest {

    @Test
    void 定位模拟器应清楚标识场景并提供受控异常模板() throws Exception {
        String html = resource("/static/index.html");
        String script = resource("/static/app.js");

        assertThat(html).contains(
                "id=\"locationScenarioPreset\"",
                "id=\"applyLocationTemplateButton\"",
                "模拟定位数据",
                "LOW_QUALITY",
                "DUPLICATE",
                "OUT_OF_ORDER",
                "UNBOUND_TAG",
                "OUT_OF_BOUNDS");
        assertThat(script).contains(
                "locationTemplate",
                "locationScenarioPreset",
                "${locationScenario:");
    }

    @Test
    void 统计卡片应提供筛选或定位动作() throws Exception {
        String html = resource("/static/index.html");

        assertThat(html).contains(
                "data-metric-action=\"ALL\"",
                "data-metric-action=\"CONNECTED\"",
                "data-metric-action=\"ACTIVITY\"",
                "data-metric-action=\"FAILED\"");
    }

    @Test
    void 未满足前置条件的设备操作应可点击并解释原因() throws Exception {
        String script = resource("/static/app.js");

        assertThat(script)
                .contains("data-blocked-reason", "状态已刷新", "handleMetricAction")
                .doesNotContain("? \"disabled\" : \"\"");
    }

    @Test
    void 运行日志导航应切换为独立日志视图() throws Exception {
        String html = resource("/static/index.html");
        String script = resource("/static/app.js");
        String styles = resource("/static/styles.css");

        assertThat(html).contains(
                "data-view=\"devices\"",
                "data-view=\"logs\"",
                "id=\"workspace\"",
                "id=\"logs\"",
                "id=\"logDeviceSelect\"");
        assertThat(script).contains(
                "function setActiveView",
                "hashchange",
                "classList.toggle(\"log-view\"");
        assertThat(styles).contains(
                ".workspace.log-view",
                ".workspace.log-view .device-panel");
    }

    @Test
    void 传输模式应切换连接参数并提供可访问帮助() throws Exception {
        String html = resource("/static/index.html");
        String script = resource("/static/app.js");
        String styles = resource("/static/styles.css");

        assertThat(html).contains(
                "id=\"endpointHostLabel\"",
                "id=\"endpointPortLabel\"",
                "class=\"help-tooltip\"",
                "data-tooltip=",
                "aria-label=\"帮助：");
        assertThat(script).contains(
                "const transportPresets",
                "hostLabel: \"Broker 地址\"",
                "hostLabel: \"平台地址\"",
                "hostLabel: \"监听地址\"",
                "form.elements.host.value = preset.host",
                "form.elements.payloadTemplate.value = templates[mode]");
        assertThat(styles).contains(
                ".help-tooltip:hover::after",
                ".help-tooltip:focus::after",
                ".help-tooltip::after { content: attr(data-tooltip); position: absolute; bottom: calc(100% + 9px); left: 0;",
                ".checkbox-label .help-tooltip::after",
                "[hidden] { display: none !important; }");
    }

    @Test
    void 添加设备应提供面向新手的四步向导() throws Exception {
        String html = resource("/static/index.html");
        String script = resource("/static/app.js");
        String styles = resource("/static/styles.css");

        assertThat(html).contains(
                "data-wizard-step=\"1\"",
                "data-wizard-step=\"2\"",
                "data-wizard-step=\"3\"",
                "data-wizard-step=\"4\"",
                "value=\"MQTT\" checked",
                "value=\"TCP_CLIENT\"",
                "value=\"TCP_SERVER\"",
                "id=\"wizardPreviousButton\"",
                "id=\"wizardNextButton\"",
                "id=\"wizardSummary\"");
        assertThat(script).contains(
                "function setWizardStep",
                "function validateWizardStep",
                "function renderWizardSummary",
                "[name=\"connectionScenario\"]");
        assertThat(styles).contains(
                ".wizard-progress",
                ".scenario-option",
                ".wizard-step");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("静态资源不存在：" + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
