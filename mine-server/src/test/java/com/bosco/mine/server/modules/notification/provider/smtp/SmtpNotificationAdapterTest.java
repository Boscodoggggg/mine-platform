package com.bosco.mine.server.modules.notification.provider.smtp;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpNotificationAdapterTest {

    @Test
    void 映射数据库SMTP参数并在发送成功后返回已受理() {
        RecordingGateway gateway = new RecordingGateway();
        SmtpNotificationAdapter adapter = new SmtpNotificationAdapter(gateway);

        NotificationDeliveryResult result = adapter.deliver(request(Map.of(
                "host", "smtp.example.com", "port", 587,
                "securityMode", "STARTTLS", "fromAddress", "alarm@example.com",
                "fromName", "智慧矿山", "replyTo", "ops@example.com"), Map.of(
                "username", "smtp-user", "password", "smtp-pass")));

        assertThat(gateway.command.host()).isEqualTo("smtp.example.com");
        assertThat(gateway.command.port()).isEqualTo(587);
        assertThat(gateway.command.securityMode()).isEqualTo(SmtpMailGateway.SecurityMode.STARTTLS);
        assertThat(gateway.command.username()).isEqualTo("smtp-user");
        assertThat(gateway.command.password()).isEqualTo("smtp-pass");
        assertThat(gateway.command.recipient()).isEqualTo("receiver@example.com");
        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.SUBMITTED);
    }

    @Test
    void 用户名和密码必须同时配置() {
        SmtpNotificationAdapter adapter = new SmtpNotificationAdapter(command -> { });

        assertThatThrownBy(() -> adapter.validateConfiguration(Map.of(
                "host", "smtp.example.com", "port", 587,
                "securityMode", "STARTTLS", "fromAddress", "alarm@example.com"),
                Map.of("username", "smtp-user")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 网关不确定异常不会被盲目重试() {
        SmtpNotificationAdapter adapter = new SmtpNotificationAdapter(command -> {
            throw new SmtpMailGateway.DeliveryException(
                    SmtpMailGateway.FailureKind.UNKNOWN, "SMTP_TRANSPORT_UNCERTAIN");
        });

        NotificationDeliveryResult result = adapter.deliver(request(Map.of(
                "host", "smtp.example.com", "port", 465,
                "securityMode", "SMTPS", "fromAddress", "alarm@example.com"), Map.of()));

        assertThat(result.outcome()).isEqualTo(NotificationDeliveryResult.Outcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("SMTP_TRANSPORT_UNCERTAIN");
    }

    private static NotificationDeliveryRequest request(
            Map<String, Object> configuration,
            Map<String, Object> credentials
    ) {
        return new NotificationDeliveryRequest(
                UUID.fromString("018f0000-0000-7000-8000-000000000003"),
                "alarm:42:email:1", "receiver@example.com", "边坡告警", "请立即处置",
                Map.of(), configuration, credentials);
    }

    private static final class RecordingGateway implements SmtpMailGateway {
        private Command command;

        @Override
        public void send(Command command) {
            this.command = command;
        }
    }
}
