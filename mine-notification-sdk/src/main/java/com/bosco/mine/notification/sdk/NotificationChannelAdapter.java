package com.bosco.mine.notification.sdk;

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
     * 执行一次逻辑投递，不得自行访问平台业务数据库。
     *
     * @param request 已校验且不可变的投递请求
     * @return 受理、送达、失败或未知结果
     */
    NotificationDeliveryResult deliver(NotificationDeliveryRequest request);
}
