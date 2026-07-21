package com.bosco.mine.server.modules.notification.provider.smtp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JakartaSmtpMailGatewayTest {

    @Test
    void StartTls和Smtps必须显式启用证书主机名校验() {
        JakartaSmtpMailGateway gateway = new JakartaSmtpMailGateway();

        assertThat(gateway.properties(command(SmtpMailGateway.SecurityMode.STARTTLS), "smtp"))
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        assertThat(gateway.properties(command(SmtpMailGateway.SecurityMode.SMTPS), "smtps"))
                .containsEntry("mail.smtps.ssl.enable", "true")
                .containsEntry("mail.smtps.ssl.checkserveridentity", "true");
        assertThat(command(SmtpMailGateway.SecurityMode.SMTPS).toString())
                .doesNotContain("receiver@example.com", "alarm@example.com", "告警", "正文");
    }

    private SmtpMailGateway.Command command(SmtpMailGateway.SecurityMode mode) {
        return new SmtpMailGateway.Command(
                "smtp.example.com", mode == SmtpMailGateway.SecurityMode.SMTPS ? 465 : 587,
                mode, null, null, "alarm@example.com", null, null,
                "receiver@example.com", "告警", "正文", 3_000, 5_000);
    }
}
