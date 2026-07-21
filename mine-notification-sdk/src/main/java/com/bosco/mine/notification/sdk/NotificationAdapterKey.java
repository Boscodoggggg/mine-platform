package com.bosco.mine.notification.sdk;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 唯一标识一个渠道类型和供应商实现。
 *
 * @param channelType 稳定渠道类型
 * @param provider    小写供应商编码
 */
public record NotificationAdapterKey(
        NotificationChannelType channelType,
        String provider
) {
    private static final Pattern PROVIDER = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    /**
     * 校验适配器键并规范化供应商编码。
     */
    public NotificationAdapterKey {
        Objects.requireNonNull(channelType, "通知渠道类型不能为空");
        if (provider == null || !PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException("通知供应商编码格式不合法");
        }
    }
}
