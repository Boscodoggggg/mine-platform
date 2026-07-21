package com.bosco.mine.server.modules.notification.provider.aliyun;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunNotificationAdapterTest {

    @Test
    void 短信映射官方请求并保存BizId() {
        RecordingSmsGateway gateway = new RecordingSmsGateway(
                new AliyunSmsGateway.Response("OK", "biz-123"));
        AliyunSmsNotificationAdapter adapter = new AliyunSmsNotificationAdapter(
                gateway, JsonMapper.builder().build());

        NotificationDeliveryResult result = adapter.deliver(request("13800138000", Map.of(
                "endpoint", "dysmsapi.aliyuncs.com", "regionId", "cn-hangzhou",
                "signName", "智慧矿山", "templateCode", "SMS_123"), Map.of(
                "accessKeyId", "ak", "accessKeySecret", "sk")));

        assertThat(gateway.command.phoneNumbers()).isEqualTo("13800138000");
        assertThat(gateway.command.signName()).isEqualTo("智慧矿山");
        assertThat(gateway.command.templateCode()).isEqualTo("SMS_123");
        assertThat(gateway.command.templateParamJson()).contains("alarmId", "42");
        assertThat(result.providerMessageId()).isEqualTo("biz-123");
        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.SUBMITTED);
    }

    @Test
    void 语音映射Tts参数并保存CallId() {
        RecordingVoiceGateway gateway = new RecordingVoiceGateway(
                new AliyunVoiceGateway.Response("OK", "call-123"));
        AliyunVoiceNotificationAdapter adapter = new AliyunVoiceNotificationAdapter(
                gateway, JsonMapper.builder().build());

        NotificationDeliveryResult result = adapter.deliver(request("13800138000", Map.of(
                "endpoint", "dyvmsapi.aliyuncs.com", "regionId", "cn-hangzhou",
                "calledShowNumber", "05710000", "ttsCode", "TTS_123",
                "playTimes", 2, "volume", 80, "speed", 0), Map.of(
                "accessKeyId", "ak", "accessKeySecret", "sk")));

        assertThat(gateway.command.phoneNumber()).isEqualTo("13800138000");
        assertThat(gateway.command.calledShowNumber()).isEqualTo("05710000");
        assertThat(gateway.command.ttsCode()).isEqualTo("TTS_123");
        assertThat(gateway.command.ttsParamJson()).contains("alarmId", "42");
        assertThat(gateway.command.outId()).hasSize(15);
        assertThat(result.providerMessageId()).isEqualTo("call-123");
    }

    @Test
    void 供应商限流与拒绝使用稳定低基数分类() {
        AliyunSmsNotificationAdapter rateLimited = new AliyunSmsNotificationAdapter(
                command -> new AliyunSmsGateway.Response("isv.BUSINESS_LIMIT_CONTROL", null),
                JsonMapper.builder().build());
        AliyunSmsNotificationAdapter rejected = new AliyunSmsNotificationAdapter(
                command -> new AliyunSmsGateway.Response("isv.MOBILE_NUMBER_ILLEGAL", null),
                JsonMapper.builder().build());

        NotificationDeliveryResult retryable = rateLimited.deliver(request(
                "13800138000", smsConfiguration(), credentials()));
        NotificationDeliveryResult permanent = rejected.deliver(request(
                "13900139000", smsConfiguration(), credentials()));

        assertThat(retryable.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.RETRYABLE_FAILURE);
        assertThat(retryable.errorCode()).isEqualTo("ALIYUN_RATE_LIMITED");
        assertThat(permanent.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.PERMANENT_FAILURE);
        assertThat(permanent.errorCode()).isEqualTo("ALIYUN_REJECTED");
    }

    @Test
    void SDK异常归类为不确定且语音参数执行本地边界校验() {
        AliyunSmsNotificationAdapter sms = new AliyunSmsNotificationAdapter(command -> {
            throw new AliyunGatewayException(new IllegalStateException("network"));
        }, JsonMapper.builder().build());
        AliyunVoiceNotificationAdapter voice = new AliyunVoiceNotificationAdapter(
                command -> new AliyunVoiceGateway.Response("OK", "call"),
                JsonMapper.builder().build());

        NotificationDeliveryResult uncertain = sms.deliver(request(
                "13800138000", smsConfiguration(), credentials()));

        assertThat(uncertain.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.UNKNOWN);
        assertThat(uncertain.errorCode()).isEqualTo("ALIYUN_DELIVERY_UNCERTAIN");
        assertThatThrownBy(() -> voice.validateConfiguration(Map.of(
                "endpoint", "dyvmsapi.aliyuncs.com", "regionId", "cn-hangzhou",
                "calledShowNumber", "05710000", "ttsCode", "TTS_123",
                "playTimes", 4), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NotificationDeliveryRequest request(
            String recipient,
            Map<String, Object> configuration,
            Map<String, Object> credentials
    ) {
        return new NotificationDeliveryRequest(
                UUID.fromString("018f0000-0000-7000-8000-000000000004"),
                "alarm:42:aliyun:1", recipient, "边坡告警", "请立即处置",
                Map.of("alarmId", "42", "level", "HIGH"), configuration, credentials);
    }

    private static Map<String, Object> smsConfiguration() {
        return Map.of(
                "endpoint", "dysmsapi.aliyuncs.com", "regionId", "cn-hangzhou",
                "signName", "智慧矿山", "templateCode", "SMS_123");
    }

    private static Map<String, Object> credentials() {
        return Map.of("accessKeyId", "ak", "accessKeySecret", "sk");
    }

    private static final class RecordingSmsGateway implements AliyunSmsGateway {
        private final Response response;
        private Command command;

        private RecordingSmsGateway(Response response) {
            this.response = response;
        }

        @Override
        public Response send(Command command) {
            this.command = command;
            return response;
        }
    }

    private static final class RecordingVoiceGateway implements AliyunVoiceGateway {
        private final Response response;
        private Command command;

        private RecordingVoiceGateway(Response response) {
            this.response = response;
        }

        @Override
        public Response call(Command command) {
            this.command = command;
            return response;
        }
    }
}
