package com.bosco.mine.server.modules.notification.provider.http;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 使用 JVM DNS 解析器提供通知端点地址快照。
 */
@Component
public class JdkHostAddressResolver implements HostAddressResolver {

    /** {@inheritDoc} */
    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
