package com.bosco.mine.server.modules.notification.adapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationAdapterContractTest {

    @Test
    void 适配器键必须由固定渠道类型和规范供应商编码组成() {
        NotificationAdapterKey key = new NotificationAdapterKey(
                NotificationChannelType.SMS, "aliyun-sms");

        assertThat(key.channelType()).isEqualTo(NotificationChannelType.SMS);
        assertThat(key.provider()).isEqualTo("aliyun-sms");
        assertThatThrownBy(() -> new NotificationAdapterKey(
                NotificationChannelType.SMS, "Aliyun SMS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 投递请求必须递归复制变量和渠道配置() {
        List<Object> nested = new ArrayList<>(List.of("critical"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("labels", nested);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("timeoutSeconds", 3);
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("password", "secret");

        NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                UUID.randomUUID(), "delivery-001", "13800000000",
                "严重告警", "矿卡温度超过阈值", variables, configuration, credentials);
        nested.add("changed");
        variables.put("late", true);
        configuration.put("timeoutSeconds", 9);
        credentials.put("password", "changed");

        assertThat(request.templateVariables()).containsOnlyKeys("labels");
        assertThat(request.templateVariables().get("labels"))
                .isEqualTo(List.of("critical"));
        assertThat(request.configuration()).containsEntry("timeoutSeconds", 3);
        assertThat(request.credentials()).containsEntry("password", "secret");
        assertThat(request.toString()).doesNotContain(
                "13800000000", "严重告警", "矿卡温度超过阈值", "secret");
        assertThatThrownBy(() -> request.templateVariables().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 投递请求必须拒绝空目标和越界正文() {
        UUID deliveryId = UUID.randomUUID();
        assertThatThrownBy(() -> new NotificationDeliveryRequest(
                deliveryId, "delivery-001", " ", "严重告警", "正文",
                Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationDeliveryRequest(
                deliveryId, "delivery-001", "ops@example.com", "严重告警",
                "x".repeat(16_385), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 适配器结果必须区分受理送达失败和未知() {
        NotificationDeliveryResult submitted = NotificationDeliveryResult.submitted("biz-001");
        NotificationDeliveryResult delivered = NotificationDeliveryResult.delivered("msg-001");
        NotificationDeliveryResult retryable = NotificationDeliveryResult.retryableFailure(
                "PROVIDER_RATE_LIMITED", Instant.parse("2026-07-21T02:00:00Z"));
        NotificationDeliveryResult permanent = NotificationDeliveryResult.permanentFailure(
                "RECIPIENT_INVALID");
        NotificationDeliveryResult unknown = NotificationDeliveryResult.unknown("REQUEST_TIMEOUT");

        assertThat(List.of(submitted.outcome(), delivered.outcome(), retryable.outcome(),
                permanent.outcome(), unknown.outcome())).containsExactly(
                NotificationDeliveryResult.Outcome.SUBMITTED,
                NotificationDeliveryResult.Outcome.DELIVERED,
                NotificationDeliveryResult.Outcome.RETRYABLE_FAILURE,
                NotificationDeliveryResult.Outcome.PERMANENT_FAILURE,
                NotificationDeliveryResult.Outcome.UNKNOWN);
        assertThat(retryable.retryAt()).contains(Instant.parse("2026-07-21T02:00:00Z"));
        assertThat(unknown.retryAt()).isEmpty();
    }

    @Test
    void 未知结果不得携带自动重试时间且供应商标识必须受控() {
        assertThatThrownBy(() -> new NotificationDeliveryResult(
                NotificationDeliveryResult.Outcome.UNKNOWN,
                null,
                "REQUEST_TIMEOUT",
                Instant.parse("2026-07-21T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationDeliveryResult.submitted("bad provider id"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
