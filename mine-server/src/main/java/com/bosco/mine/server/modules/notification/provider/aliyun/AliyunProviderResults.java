package com.bosco.mine.server.modules.notification.provider.aliyun;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 将阿里云业务响应码转换为平台稳定低基数结果。
 */
final class AliyunProviderResults {

    private AliyunProviderResults() {
    }

    /** 转换短信或语音业务响应。 */
    static NotificationDeliveryResult from(String code, String providerMessageId) {
        if ("OK".equalsIgnoreCase(code)) {
            if (providerMessageId == null || providerMessageId.isBlank()) {
                return NotificationDeliveryResult.unknown("ALIYUN_RESPONSE_INVALID");
            }
            return NotificationDeliveryResult.submitted(providerMessageId);
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        if (normalized.contains("LIMIT") || normalized.contains("THROTTLING")
                || normalized.contains("SYSTEM_ERROR") || normalized.contains("SERVICE_BUSY")) {
            return NotificationDeliveryResult.retryableFailure(
                    "ALIYUN_RATE_LIMITED", Instant.now().plus(60, ChronoUnit.SECONDS));
        }
        return NotificationDeliveryResult.permanentFailure("ALIYUN_REJECTED");
    }
}
