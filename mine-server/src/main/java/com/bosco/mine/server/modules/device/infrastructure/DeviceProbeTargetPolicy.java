package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 限制设备诊断可访问的矿区网络范围，避免利用诊断接口探测服务端管理网络。
 */
@Component
public class DeviceProbeTargetPolicy {

    private final List<CidrRange> allowedRanges;
    private final boolean allowSensitiveAddresses;

    /**
     * 创建目标地址策略。
     *
     * @param allowedCidrs 允许访问的 CIDR 列表，多个范围使用逗号分隔
     */
    @Autowired
    public DeviceProbeTargetPolicy(@Value("${mine.device-connection.allowed-cidrs:}") String allowedCidrs) {
        this(allowedCidrs, false);
    }

    /**
     * 创建可供隔离测试环境使用的目标地址策略。
     *
     * @param allowedCidrs 允许访问的 CIDR 列表
     * @param allowSensitiveAddresses 是否允许本机等敏感地址，仅测试环境可设为真
     */
    DeviceProbeTargetPolicy(String allowedCidrs, boolean allowSensitiveAddresses) {
        this.allowedRanges = Arrays.stream(allowedCidrs.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(CidrRange::parse)
                .toList();
        this.allowSensitiveAddresses = allowSensitiveAddresses;
    }

    /**
     * 审核解析结果。只有全部地址均位于允许范围且不属于敏感地址时才返回候选地址。
     *
     * @param addresses DNS 解析地址
     * @return 审核通过并可固定使用的地址；任一地址越界时返回空列表
     */
    public List<InetAddress> filterAllowed(List<InetAddress> addresses) {
        List<InetAddress> accepted = new ArrayList<>();
        for (InetAddress address : addresses) {
            if ((!allowSensitiveAddresses && isSensitive(address))
                    || allowedRanges.stream().noneMatch(range -> range.contains(address))) {
                return List.of();
            }
            accepted.add(address);
        }
        return List.copyOf(accepted);
    }

    private boolean isSensitive(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress();
    }

    /**
     * CIDR 地址范围。
     *
     * @param network 网络地址字节
     * @param prefixLength 前缀位数
     */
    private record CidrRange(byte[] network, int prefixLength) {

        private static CidrRange parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("设备诊断允许网段格式错误: " + value);
                }
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > network.length * Byte.SIZE) {
                    throw new IllegalArgumentException("设备诊断允许网段前缀错误: " + value);
                }
                return new CidrRange(network, prefix);
            } catch (Exception exception) {
                throw new IllegalArgumentException("无法解析设备诊断允许网段: " + value, exception);
            }
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
