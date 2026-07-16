package com.bosco.mine.server.modules.device.infrastructure;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验设备诊断目标只能位于显式允许的矿区网络范围。
 */
class DeviceProbeTargetPolicyTest {

    private final DeviceProbeTargetPolicy policy = new DeviceProbeTargetPolicy(
            "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7"
    );

    @Test
    void 应允许矿区私网并拒绝本机链路本地和公网地址() throws Exception {
        assertThat(policy.filterAllowed(List.of(InetAddress.getByName("10.12.3.4")))).hasSize(1);
        assertThat(policy.filterAllowed(List.of(InetAddress.getByName("127.0.0.1")))).isEmpty();
        assertThat(policy.filterAllowed(List.of(InetAddress.getByName("169.254.169.254")))).isEmpty();
        assertThat(policy.filterAllowed(List.of(InetAddress.getByName("8.8.8.8")))).isEmpty();
    }

    @Test
    void 任一解析地址越界时应拒绝整个域名以防止重绑定() throws Exception {
        assertThat(policy.filterAllowed(List.of(
                InetAddress.getByName("10.12.3.4"), InetAddress.getByName("8.8.8.8")
        ))).isEmpty();
    }

    @Test
    void 未配置允许网段时应默认拒绝所有目标() throws Exception {
        DeviceProbeTargetPolicy denyAll = new DeviceProbeTargetPolicy("");

        assertThat(denyAll.filterAllowed(List.of(InetAddress.getByName("10.12.3.4")))).isEmpty();
    }
}
