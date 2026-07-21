package com.bosco.mine.server.modules.notification.provider.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 为通知端点安全校验解析全部目标地址。
 */
@FunctionalInterface
public interface HostAddressResolver {

    /**
     * 解析主机当前对应的全部地址。
     *
     * @param host 规范化主机名
     * @return 全部解析地址
     * @throws UnknownHostException 主机无法解析
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
