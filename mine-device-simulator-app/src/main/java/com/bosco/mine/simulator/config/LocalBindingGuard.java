package com.bosco.mine.simulator.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 限制管理页面只能绑定回环地址，避免无认证控制接口误暴露到局域网。
 */
public final class LocalBindingGuard {

    /**
     * 校验管理页面监听地址。
     *
     * @param bindAddress 监听地址
     * @param allowNonLoopback 是否经过部署层明确允许非回环监听
     */
    public LocalBindingGuard(String bindAddress, boolean allowNonLoopback) {
        try {
            InetAddress address = InetAddress.getByName(bindAddress);
            if (!address.isLoopbackAddress() && !allowNonLoopback) {
                throw new IllegalArgumentException("模拟器管理页面仅允许绑定本机回环地址，当前地址：" + bindAddress);
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("无法识别模拟器管理页面监听地址：" + bindAddress, exception);
        }
    }
}
