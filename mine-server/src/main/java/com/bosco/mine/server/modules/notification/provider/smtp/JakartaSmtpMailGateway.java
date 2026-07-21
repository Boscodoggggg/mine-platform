package com.bosco.mine.server.modules.notification.provider.smtp;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 使用 Jakarta Mail 执行真实 SMTP 发送。
 */
@Component
public class JakartaSmtpMailGateway implements SmtpMailGateway {

    /** {@inheritDoc} */
    @Override
    public void send(Command command) {
        String protocol = command.securityMode() == SecurityMode.SMTPS ? "smtps" : "smtp";
        Session session = Session.getInstance(properties(command, protocol));
        MimeMessage message = message(session, command);
        Transport transport;
        try {
            transport = session.getTransport(protocol);
            transport.connect(command.host(), command.port(), command.username(), command.password());
        } catch (AuthenticationFailedException exception) {
            throw new DeliveryException(FailureKind.PERMANENT, "SMTP_AUTHENTICATION_FAILED");
        } catch (MessagingException exception) {
            throw new DeliveryException(FailureKind.RETRYABLE, "SMTP_CONNECT_FAILED");
        }
        try (transport) {
            transport.sendMessage(message, message.getAllRecipients());
        } catch (SendFailedException exception) {
            throw new DeliveryException(FailureKind.PERMANENT, "SMTP_RECIPIENT_REJECTED");
        } catch (MessagingException exception) {
            throw new DeliveryException(FailureKind.UNKNOWN, "SMTP_TRANSPORT_UNCERTAIN");
        }
    }

    private Properties properties(Command command, String protocol) {
        Properties properties = new Properties();
        String prefix = "mail." + protocol + ".";
        properties.setProperty(prefix + "auth", String.valueOf(command.username() != null));
        properties.setProperty(prefix + "connectiontimeout", String.valueOf(command.connectTimeoutMs()));
        properties.setProperty(prefix + "timeout", String.valueOf(command.readTimeoutMs()));
        properties.setProperty(prefix + "writetimeout", String.valueOf(command.readTimeoutMs()));
        if (command.securityMode() == SecurityMode.STARTTLS) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
        }
        if (command.securityMode() == SecurityMode.SMTPS) {
            properties.setProperty("mail.smtps.ssl.enable", "true");
        }
        return properties;
    }

    private MimeMessage message(Session session, Command command) {
        try {
            MimeMessage message = new MimeMessage(session);
            InternetAddress from = command.fromName() == null
                    ? new InternetAddress(command.fromAddress(), true)
                    : new InternetAddress(command.fromAddress(), command.fromName(),
                            StandardCharsets.UTF_8.name());
            message.setFrom(from);
            message.setRecipient(Message.RecipientType.TO,
                    new InternetAddress(command.recipient(), true));
            if (command.replyTo() != null) {
                message.setReplyTo(new InternetAddress[]{
                        new InternetAddress(command.replyTo(), true)});
            }
            message.setSubject(command.subject(), StandardCharsets.UTF_8.name());
            message.setText(command.body(), StandardCharsets.UTF_8.name(), "plain");
            message.saveChanges();
            return message;
        } catch (MessagingException | java.io.UnsupportedEncodingException exception) {
            throw new DeliveryException(FailureKind.PERMANENT, "SMTP_MESSAGE_INVALID");
        }
    }
}
