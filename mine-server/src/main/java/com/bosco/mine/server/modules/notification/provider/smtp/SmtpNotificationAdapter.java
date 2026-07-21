package com.bosco.mine.server.modules.notification.provider.smtp;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 平台通用 SMTP 邮件通知 Provider。
 */
@Component
public class SmtpNotificationAdapter implements NotificationChannelAdapter {

    private static final NotificationAdapterKey KEY =
            new NotificationAdapterKey(NotificationChannelType.EMAIL, "smtp");
    private static final NotificationProviderDefinition DEFINITION =
            new NotificationProviderDefinition(
                    KEY, "mine.notification.provider.smtp", false,
                    List.of(
                            NotificationProviderSupport.stringField("host", true, 253, null),
                            NotificationProviderSupport.integerField("port", true, 587),
                            NotificationProviderSupport.stringField(
                                    "securityMode", true, 16, "STARTTLS",
                                    "NONE", "STARTTLS", "SMTPS"),
                            NotificationProviderSupport.stringField("fromAddress", true, 320, null),
                            NotificationProviderSupport.stringField("fromName", false, 128, null),
                            NotificationProviderSupport.stringField("replyTo", false, 320, null)),
                    List.of(
                            NotificationProviderSupport.secretField("username", false, 512),
                            NotificationProviderSupport.secretField("password", false, 4096)));

    private final SmtpMailGateway gateway;

    /** 创建 SMTP Provider。 */
    public SmtpNotificationAdapter(SmtpMailGateway gateway) {
        this.gateway = gateway;
    }

    /** {@inheritDoc} */
    @Override
    public NotificationAdapterKey key() {
        return KEY;
    }

    /** {@inheritDoc} */
    @Override
    public NotificationProviderDefinition definition() {
        return DEFINITION;
    }

    /** {@inheritDoc} */
    @Override
    public void validateConfiguration(
            Map<String, Object> configuration,
            Map<String, String> credentials
    ) {
        String host = NotificationProviderSupport.requiredString(configuration, "host");
        if (!host.matches("^(?=.{1,253}$)[A-Za-z0-9.-]+$") || host.startsWith(".")
                || host.endsWith(".")) {
            throw new IllegalArgumentException("SMTP 主机名格式不合法");
        }
        int port = NotificationProviderSupport.integer(configuration, "port", 587);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP 端口范围不合法");
        }
        securityMode(configuration);
        validateAddress(NotificationProviderSupport.requiredString(configuration, "fromAddress"));
        String replyTo = NotificationProviderSupport.optionalString(configuration, "replyTo");
        if (replyTo != null) {
            validateAddress(replyTo);
        }
        String username = NotificationProviderSupport.optionalString(credentials, "username");
        String password = NotificationProviderSupport.optionalString(credentials, "password");
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("SMTP 用户名和密码必须同时配置");
        }
    }

    /** {@inheritDoc} */
    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        try {
            gateway.send(new SmtpMailGateway.Command(
                    NotificationProviderSupport.requiredString(request.configuration(), "host"),
                    NotificationProviderSupport.integer(request.configuration(), "port", 587),
                    securityMode(request.configuration()),
                    NotificationProviderSupport.optionalString(request.credentials(), "username"),
                    NotificationProviderSupport.optionalString(request.credentials(), "password"),
                    NotificationProviderSupport.requiredString(
                            request.configuration(), "fromAddress"),
                    NotificationProviderSupport.optionalString(request.configuration(), "fromName"),
                    NotificationProviderSupport.optionalString(request.configuration(), "replyTo"),
                    request.recipient(), request.subject(), request.body(),
                    request.connectTimeoutMs(), request.readTimeoutMs()));
            return NotificationDeliveryResult.submitted(null);
        } catch (SmtpMailGateway.DeliveryException exception) {
            return switch (exception.failureKind()) {
                case RETRYABLE -> NotificationDeliveryResult.retryableFailure(
                        exception.errorCode(), Instant.now().plus(30, ChronoUnit.SECONDS));
                case PERMANENT -> NotificationDeliveryResult.permanentFailure(exception.errorCode());
                case UNKNOWN -> NotificationDeliveryResult.unknown(exception.errorCode());
            };
        }
    }

    private SmtpMailGateway.SecurityMode securityMode(Map<String, ?> configuration) {
        String mode = NotificationProviderSupport.requiredString(configuration, "securityMode");
        try {
            return SmtpMailGateway.SecurityMode.valueOf(mode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("SMTP 安全模式不受支持");
        }
    }

    private void validateAddress(String address) {
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
        } catch (AddressException exception) {
            throw new IllegalArgumentException("SMTP 邮箱地址格式不合法");
        }
    }
}
