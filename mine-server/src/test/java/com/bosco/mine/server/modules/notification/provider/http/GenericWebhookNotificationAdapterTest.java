package com.bosco.mine.server.modules.notification.provider.http;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericWebhookNotificationAdapterTest {

    @Test
    void 发送稳定事件结构并对原始正文签名() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        GenericWebhookNotificationAdapter adapter = new GenericWebhookNotificationAdapter(
                transport,
                new NotificationEndpointPolicy(host -> List.of(InetAddress.getByName("8.8.8.8"))),
                JsonMapper.builder().build());
        NotificationDeliveryRequest request = request(Map.of(
                "endpointUrl", "https://hooks.example.com/alarm",
                "allowedHosts", List.of("hooks.example.com"),
                "signatureHeader", "X-Mine-Signature"), Map.of(
                "signingSecret", "hook-secret",
                "bearerToken", "bearer-secret"));

        NotificationDeliveryResult result = adapter.deliver(request);

        JsonNode payload = JsonMapper.builder().build().readTree(transport.request.body());
        String expectedSignature = "sha256=" + hmacHex(transport.request.body(), "hook-secret");
        assertThat(payload.path("deliveryId").asText()).isEqualTo(request.deliveryId().toString());
        assertThat(payload.path("idempotencyKey").asText()).isEqualTo(request.idempotencyKey());
        assertThat(payload.path("variables").path("alarmId").asText()).isEqualTo("42");
        assertThat(transport.request.headers())
                .containsEntry("X-Mine-Signature", expectedSignature)
                .containsEntry("Authorization", "Bearer bearer-secret");
        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.SUBMITTED);
    }

    @Test
    void 配置校验拒绝不在白名单内的地址() throws Exception {
        GenericWebhookNotificationAdapter adapter = new GenericWebhookNotificationAdapter(
                request -> new NotificationHttpTransport.Response(204, "", Map.of()),
                new NotificationEndpointPolicy(host -> List.of(InetAddress.getByName("8.8.8.8"))),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> adapter.validateConfiguration(Map.of(
                "endpointUrl", "https://evil.example.com/hook",
                "allowedHosts", List.of("hooks.example.com")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 配置校验拒绝可注入请求头的凭据() throws Exception {
        GenericWebhookNotificationAdapter adapter = new GenericWebhookNotificationAdapter(
                request -> new NotificationHttpTransport.Response(204, "", Map.of()),
                new NotificationEndpointPolicy(host -> List.of(InetAddress.getByName("8.8.8.8"))),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> adapter.validateConfiguration(Map.of(
                "endpointUrl", "https://hooks.example.com/hook",
                "allowedHosts", List.of("hooks.example.com")),
                Map.of("bearerToken", "token\r\nX-Evil: true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NotificationDeliveryRequest request(
            Map<String, Object> configuration,
            Map<String, Object> credentials
    ) {
        return new NotificationDeliveryRequest(
                UUID.fromString("018f0000-0000-7000-8000-000000000002"),
                "alarm:42:webhook:1", "hooks.example.com", "边坡告警", "请立即处置",
                Map.of("alarmId", "42"), configuration, credentials);
    }

    private static String hmacHex(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingTransport implements NotificationHttpTransport {
        private Request request;

        @Override
        public Response post(Request request) {
            this.request = request;
            return new Response(202, "", Map.of());
        }
    }
}
