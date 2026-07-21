package com.bosco.mine.server.modules.notification.provider.smtp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 隔离 SMTP Provider 与 Jakarta Mail 传输实现。
 */
@FunctionalInterface
public interface SmtpMailGateway {

    /** 执行一次 SMTP 投递。 */
    void send(Command command);

    /**
     * SMTP 投递命令。
     *
     * @param host 邮件服务器主机
     * @param port 端口
     * @param securityMode 传输安全模式
     * @param username 用户名
     * @param password 密码
     * @param fromAddress 发件地址
     * @param fromName 发件人名称
     * @param replyTo 回复地址
     * @param recipient 收件地址
     * @param subject 邮件标题
     * @param body 邮件纯文本正文
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs 读取超时
     */
    record Command(
            String host,
            int port,
            SecurityMode securityMode,
            String username,
            String password,
            String fromAddress,
            String fromName,
            String replyTo,
            String recipient,
            String subject,
            String body,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        /** 校验 SMTP 命令基本边界。 */
        public Command {
            Objects.requireNonNull(host, "SMTP 主机不能为空");
            Objects.requireNonNull(securityMode, "SMTP 安全模式不能为空");
            Objects.requireNonNull(fromAddress, "SMTP 发件地址不能为空");
            Objects.requireNonNull(recipient, "SMTP 收件地址不能为空");
            Objects.requireNonNull(subject, "SMTP 标题不能为空");
            Objects.requireNonNull(body, "SMTP 正文不能为空");
        }

        /** 返回不包含认证信息、地址和邮件正文的安全日志摘要。 */
        @Override
        public String toString() {
            return "Command[host=" + host
                    + ", port=" + port
                    + ", securityMode=" + securityMode
                    + ", connectTimeoutMs=" + connectTimeoutMs
                    + ", readTimeoutMs=" + readTimeoutMs + "]";
        }
    }

    /** SMTP 连接安全模式。 */
    enum SecurityMode {
        /** 明文 SMTP，仅用于客户明确配置的可信网络。 */
        NONE,
        /** SMTP 建连后强制升级 TLS。 */
        STARTTLS,
        /** 建连即使用 TLS。 */
        SMTPS
    }

    /** SMTP 网关稳定失败分类。 */
    enum FailureKind {
        /** 尚未发送，可安全重试。 */
        RETRYABLE,
        /** 配置或接收目标错误。 */
        PERMANENT,
        /** 可能已提交，禁止盲目重试。 */
        UNKNOWN
    }

    /**
     * 不包含邮件内容与凭据的 SMTP 网关异常。
     */
    final class DeliveryException extends RuntimeException {
        private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
        private final FailureKind failureKind;
        private final String errorCode;

        /** 创建稳定分类异常。 */
        public DeliveryException(FailureKind failureKind, String errorCode) {
            super(errorCode);
            this.failureKind = Objects.requireNonNull(failureKind, "SMTP 失败分类不能为空");
            if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
                throw new IllegalArgumentException("SMTP 错误码格式不合法");
            }
            this.errorCode = errorCode;
        }

        /** 返回稳定失败分类。 */
        public FailureKind failureKind() {
            return failureKind;
        }

        /** 返回固定低基数错误码。 */
        public String errorCode() {
            return errorCode;
        }
    }
}
