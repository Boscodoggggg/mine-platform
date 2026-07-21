package com.bosco.mine.server.modules.notification.adapter;

import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;

import java.util.Map;

/**
 * 受信通知渠道实现必须遵循的稳定适配器边界。
 */
public interface NotificationChannelAdapter {

    /**
     * 返回当前实现唯一的渠道和供应商键。
     *
     * @return 稳定适配器键
     */
    NotificationAdapterKey key();

    /**
     * 返回平台用于配置页面和通用校验的 Provider 定义。
     *
     * @return Provider 字段定义
     */
    NotificationProviderDefinition definition();

    /**
     * 执行当前 Provider 特有的配置组合校验。
     *
     * @param configuration 非敏感配置快照
     * @param credentials 解密后的凭据快照
     */
    void validateConfiguration(
            Map<String, Object> configuration,
            Map<String, String> credentials
    );

    /**
     * 执行一次逻辑投递，不得自行访问平台业务数据库。
     *
     * @param request 已校验且不可变的投递请求
     * @return 受理、送达、失败或未知结果
     */
    NotificationDeliveryResult deliver(NotificationDeliveryRequest request);
}
