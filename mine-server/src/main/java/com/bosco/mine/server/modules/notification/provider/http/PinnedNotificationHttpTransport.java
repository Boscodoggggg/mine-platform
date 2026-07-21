package com.bosco.mine.server.modules.notification.provider.http;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用策略固定的 DNS 地址执行通知请求，并强制 TLS 主机名校验、读取超时和响应上限。
 */
@Component
public class PinnedNotificationHttpTransport implements NotificationHttpTransport {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** {@inheritDoc} */
    @Override
    public Response post(Request request) {
        PoolingHttpClientConnectionManager connectionManager = connectionManager(request);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout(request.connectTimeout()))
                .setResponseTimeout(timeout(request.readTimeout()))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build()) {
            HttpPost post = new HttpPost(request.endpoint().uri());
            post.setHeader("Accept", "application/json");
            request.headers().forEach(post::setHeader);
            post.setEntity(new StringEntity(
                    request.body(), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
            return client.execute(post, response -> new Response(
                    response.getCode(),
                    new String(readBounded(response.getEntity() == null
                            ? InputStream.nullInputStream()
                            : response.getEntity().getContent()), StandardCharsets.UTF_8),
                    headers(response.getHeaders())));
        } catch (IOException exception) {
            throw new TransportException(
                    requestMayHaveBeenSent(exception)
                            ? "通知 HTTP 调用未获得确定响应"
                            : "通知 HTTP 连接建立失败",
                    requestMayHaveBeenSent(exception), null);
        }
    }

    /**
     * 在固定内存上限内读取供应商响应，底层套接字读取同时受请求超时约束。
     *
     * @param input 响应输入流
     * @return 未超过安全上限的响应字节
     */
    static byte[] readBounded(InputStream input) {
        try {
            byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new TransportException("通知供应商响应超过安全上限", true, null);
            }
            return body;
        } catch (IOException exception) {
            throw new TransportException("通知 HTTP 响应读取失败", true, null);
        }
    }

    /**
     * 判断异常是否发生在连接或 TLS 握手阶段，只有这些情况可以确认请求尚未写出。
     *
     * @param exception HTTP 客户端异常
     * @return 请求是否可能已经写出
     */
    static boolean requestMayHaveBeenSent(IOException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor instanceof UnknownHostException
                    || cursor instanceof ConnectException
                    || cursor instanceof ConnectTimeoutException
                    || cursor instanceof SSLHandshakeException) {
                return false;
            }
            cursor = cursor.getCause();
        }
        return true;
    }

    private PoolingHttpClientConnectionManager connectionManager(Request request) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout(request.connectTimeout()))
                .setSocketTimeout(timeout(request.readTimeout()))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedResolver(request.endpoint()))
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setHostVerificationPolicy(HostnameVerificationPolicy.BOTH)
                        .buildClassic())
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();
    }

    private DnsResolver pinnedResolver(NotificationEndpointPolicy.ValidatedEndpoint endpoint) {
        String expectedHost = endpoint.uri().getHost();
        InetAddress[] addresses = endpoint.addresses().toArray(InetAddress[]::new);
        return new DnsResolver() {
            /** {@inheritDoc} */
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) {
                    throw new UnknownHostException("通知 HTTP 主机不在固定解析结果中");
                }
                return addresses.clone();
            }

            /** {@inheritDoc} */
            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) {
                    throw new UnknownHostException("通知 HTTP 主机不在固定解析结果中");
                }
                return expectedHost;
            }
        };
    }

    private Timeout timeout(java.time.Duration duration) {
        return Timeout.ofMilliseconds(duration.toMillis());
    }

    private Map<String, List<String>> headers(Header[] headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Header header : headers) {
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        values.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Map.copyOf(frozen);
    }
}
