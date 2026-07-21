package com.bosco.mine.server.modules.notification.provider.http;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 使用 JDK HTTP Client 执行有界且不自动重定向的通知请求。
 */
@Component
public class JdkNotificationHttpTransport implements NotificationHttpTransport {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** {@inheritDoc} */
    @Override
    public Response post(Request request) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(request.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.endpoint())
                .timeout(request.readTimeout())
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.body()));
        request.headers().forEach(builder::header);
        try {
            HttpResponse<byte[]> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new TransportException("通知供应商响应超过安全上限", true, null);
            }
            return new Response(response.statusCode(),
                    new String(response.body(), java.nio.charset.StandardCharsets.UTF_8),
                    response.headers().map());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException("通知 HTTP 调用被中断", true, exception);
        } catch (IOException exception) {
            throw new TransportException("通知 HTTP 调用未获得响应", true, exception);
        }
    }
}
