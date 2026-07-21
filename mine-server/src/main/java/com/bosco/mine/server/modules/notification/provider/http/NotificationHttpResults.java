package com.bosco.mine.server.modules.notification.provider.http;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 将通用 HTTP 状态归一化为平台投递结果。
 */
public final class NotificationHttpResults {

    private NotificationHttpResults() {
    }

    /** 将非成功 HTTP 响应转换为稳定分类，成功时返回空值。 */
    public static NotificationDeliveryResult failure(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return null;
        }
        if (statusCode >= 500) {
            return NotificationDeliveryResult.unknown("HTTP_DELIVERY_UNCERTAIN");
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429) {
            String code = statusCode == 429 ? "HTTP_RATE_LIMITED" : "HTTP_TEMPORARY_FAILURE";
            return NotificationDeliveryResult.retryableFailure(
                    code, Instant.now().plus(60, ChronoUnit.SECONDS));
        }
        return NotificationDeliveryResult.permanentFailure("HTTP_REQUEST_REJECTED");
    }

    /** 将未获得响应的传输异常转换为防重复的稳定分类。 */
    public static NotificationDeliveryResult transportFailure(
            NotificationHttpTransport.TransportException exception
    ) {
        return exception.requestMayHaveBeenSent()
                ? NotificationDeliveryResult.unknown("HTTP_DELIVERY_UNCERTAIN")
                : NotificationDeliveryResult.retryableFailure(
                        "HTTP_CONNECT_FAILED", Instant.now().plus(30, ChronoUnit.SECONDS));
    }
}
