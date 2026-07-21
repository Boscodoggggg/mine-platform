package com.bosco.mine.server.modules.notification.provider.http;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 隔离通知 Provider 与具体 HTTP 客户端的传输边界。
 */
@FunctionalInterface
public interface NotificationHttpTransport {

    /** 执行一次 JSON POST 请求。 */
    Response post(Request request);

    /**
     * 不包含业务秘密的 HTTP 请求快照。
     *
     * @param endpoint 已通过安全策略校验的端点
     * @param headers 请求头
     * @param body JSON 正文
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     */
    record Request(
            NotificationEndpointPolicy.ValidatedEndpoint endpoint,
            Map<String, String> headers,
            String body,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        /** 校验并冻结请求。 */
        public Request {
            Objects.requireNonNull(endpoint, "通知 HTTP 端点不能为空");
            headers = Map.copyOf(Objects.requireNonNull(headers, "通知 HTTP 请求头不能为空"));
            Objects.requireNonNull(body, "通知 HTTP 正文不能为空");
            Objects.requireNonNull(connectTimeout, "通知 HTTP 连接超时不能为空");
            Objects.requireNonNull(readTimeout, "通知 HTTP 读取超时不能为空");
        }

        /** 返回不含路径、查询和凭据的安全日志摘要。 */
        @Override
        public String toString() {
            return "Request[endpointHost=" + endpoint.uri().getHost()
                    + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + "]";
        }
    }

    /**
     * 有界 HTTP 响应快照。
     *
     * @param statusCode 状态码
     * @param body 响应正文
     * @param headers 响应头
     */
    record Response(int statusCode, String body, Map<String, java.util.List<String>> headers) {
        /** 校验并冻结响应。 */
        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("通知 HTTP 状态码不合法");
            }
            body = body == null ? "" : body;
            headers = Map.copyOf(Objects.requireNonNull(headers, "通知 HTTP 响应头不能为空"));
        }

        /** 返回不包含响应正文和请求标识响应头的安全日志摘要。 */
        @Override
        public String toString() {
            return "Response[statusCode=" + statusCode
                    + ", bodyLength=" + body.length()
                    + ", headerCount=" + headers.size() + "]";
        }
    }

    /**
     * 表示没有获得 HTTP 响应的传输失败。
     */
    final class TransportException extends RuntimeException {
        private final boolean requestMayHaveBeenSent;

        /** 创建脱敏的传输异常。 */
        public TransportException(String message, boolean requestMayHaveBeenSent, Throwable cause) {
            super(message, cause);
            this.requestMayHaveBeenSent = requestMayHaveBeenSent;
        }

        /** 返回请求是否可能已经离开本机。 */
        public boolean requestMayHaveBeenSent() {
            return requestMayHaveBeenSent;
        }
    }
}
