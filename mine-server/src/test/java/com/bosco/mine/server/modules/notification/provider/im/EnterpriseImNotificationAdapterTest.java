package com.bosco.mine.server.modules.notification.provider.im;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseImNotificationAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-21T02:00:00Z");

    @Test
    void 企业微信按官方文本结构发送并识别成功响应() throws Exception {
        RecordingTransport transport = new RecordingTransport(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        WeComRobotNotificationAdapter adapter = new WeComRobotNotificationAdapter(
                transport, policy(), JsonMapper.builder().build());

        NotificationDeliveryResult result = adapter.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=secret")));

        JsonNode payload = JsonMapper.builder().build().readTree(transport.request.body());
        assertThat(adapter.key().channelType()).isEqualTo(NotificationChannelType.ENTERPRISE_IM);
        assertThat(payload.path("msgtype").asText()).isEqualTo("text");
        assertThat(payload.path("text").path("content").asText()).contains("边坡告警", "请立即处置");
        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.SUBMITTED);
    }

    @Test
    void 钉钉加签使用毫秒时间戳并写入签名查询参数() throws Exception {
        RecordingTransport transport = new RecordingTransport(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        String secret = "SEC-test-secret";
        DingTalkRobotNotificationAdapter adapter = new DingTalkRobotNotificationAdapter(
                transport, policy(), JsonMapper.builder().build(), fixedClock());

        adapter.deliver(request(Map.of(), Map.of(
                "webhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=token",
                "signingSecret", secret)));

        long timestamp = NOW.toEpochMilli();
        String expected = hmacBase64(timestamp + "\n" + secret, secret);
        assertThat(transport.request.endpoint().uri().getQuery())
                .contains("timestamp=" + timestamp)
                .contains("sign=");
        assertThat(java.net.URLDecoder.decode(
                transport.request.endpoint().uri().getRawQuery(), StandardCharsets.UTF_8))
                .contains("sign=" + expected);
    }

    @Test
    void 飞书加签写入秒级时间戳和正文签名() throws Exception {
        RecordingTransport transport = new RecordingTransport(200, "{\"code\":0,\"msg\":\"success\"}");
        String secret = "feishu-secret";
        FeishuRobotNotificationAdapter adapter = new FeishuRobotNotificationAdapter(
                transport, policy(), JsonMapper.builder().build(), fixedClock());

        adapter.deliver(request(Map.of(), Map.of(
                "webhookUrl", "https://open.feishu.cn/open-apis/bot/v2/hook/token",
                "signingSecret", secret)));

        JsonNode payload = JsonMapper.builder().build().readTree(transport.request.body());
        long timestamp = NOW.getEpochSecond();
        assertThat(payload.path("timestamp").asText()).isEqualTo(String.valueOf(timestamp));
        assertThat(payload.path("sign").asText())
                .isEqualTo(hmacBase64("", timestamp + "\n" + secret));
        assertThat(payload.path("msg_type").asText()).isEqualTo("text");
    }

    @Test
    void 限流响应归类为可重试失败() throws Exception {
        RecordingTransport transport = new RecordingTransport(429, "{}");
        WeComRobotNotificationAdapter adapter = new WeComRobotNotificationAdapter(
                transport, policy(), JsonMapper.builder().build());

        NotificationDeliveryResult result = adapter.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x")));

        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("HTTP_RATE_LIMITED");
    }

    @Test
    void 业务限流必须可重试而明确业务拒绝必须永久失败() throws Exception {
        DingTalkRobotNotificationAdapter limited = new DingTalkRobotNotificationAdapter(
                new RecordingTransport(200, "{\"errcode\":130101,\"errmsg\":\"send too fast\"}"),
                policy(), JsonMapper.builder().build(), fixedClock());
        DingTalkRobotNotificationAdapter rejected = new DingTalkRobotNotificationAdapter(
                new RecordingTransport(200, "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"),
                policy(), JsonMapper.builder().build(), fixedClock());

        NotificationDeliveryResult limitedResult = limited.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://oapi.dingtalk.com/robot/send")));
        NotificationDeliveryResult rejectedResult = rejected.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://oapi.dingtalk.com/robot/send")));

        assertThat(limitedResult.outcome())
                .isEqualTo(NotificationDeliveryResult.Outcome.RETRYABLE_FAILURE);
        assertThat(rejectedResult.outcome())
                .isEqualTo(NotificationDeliveryResult.Outcome.PERMANENT_FAILURE);
    }

    @Test
    void 畸形和服务器错误响应均不得被误判为永久失败或盲目重试() throws Exception {
        WeComRobotNotificationAdapter malformed = new WeComRobotNotificationAdapter(
                new RecordingTransport(200, "not-json"), policy(), JsonMapper.builder().build());
        WeComRobotNotificationAdapter serverError = new WeComRobotNotificationAdapter(
                new RecordingTransport(503, "{}"), policy(), JsonMapper.builder().build());

        NotificationDeliveryResult malformedResult = malformed.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send")));
        NotificationDeliveryResult serverErrorResult = serverError.deliver(request(
                Map.of(), Map.of("webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send")));

        assertThat(malformedResult.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.UNKNOWN);
        assertThat(serverErrorResult.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.UNKNOWN);
    }

    private static NotificationDeliveryRequest request(
            Map<String, Object> configuration,
            Map<String, Object> credentials
    ) {
        return new NotificationDeliveryRequest(
                UUID.fromString("018f0000-0000-7000-8000-000000000001"),
                "alarm:42:step:1", "mine-user", "边坡告警", "请立即处置",
                Map.of("alarmId", "42"), configuration, credentials);
    }

    private static NotificationEndpointPolicy policy() throws Exception {
        return new NotificationEndpointPolicy(
                host -> List.of(InetAddress.getByName("8.8.8.8")));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String hmacBase64(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingTransport implements NotificationHttpTransport {
        private final Response response;
        private Request request;

        private RecordingTransport(int status, String body) {
            this.response = new Response(status, body, Map.of());
        }

        @Override
        public Response post(Request request) {
            this.request = request;
            return response;
        }
    }
}
