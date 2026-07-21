package com.bosco.mine.server.modules.notification.provider.http;

import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对数据库配置的通知 HTTP 端点执行 HTTPS、白名单与 SSRF 地址阻断。
 */
@Component
public class NotificationEndpointPolicy {

    private static final Pattern DNS_NAME = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final HostAddressResolver resolver;

    /**
     * 创建端点策略。
     *
     * @param resolver 地址解析器
     */
    public NotificationEndpointPolicy(HostAddressResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "主机地址解析器不能为空");
    }

    /**
     * 校验端点并返回规范 URI。
     *
     * @param endpointUrl 完整 HTTPS 地址
     * @param allowedHosts 精确主机白名单
     * @return 已校验 URI
     */
    public ValidatedEndpoint validate(String endpointUrl, Set<String> allowedHosts) {
        Objects.requireNonNull(allowedHosts, "通知端点白名单不能为空");
        URI endpoint = parse(endpointUrl);
        String host = normalizeHost(endpoint.getHost());
        Set<String> normalizedAllowed = allowedHosts.stream().map(this::normalizeHost).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        if (!normalizedAllowed.contains(host)) {
            throw new IllegalArgumentException("通知端点主机不在精确白名单内");
        }
        try {
            List<InetAddress> addresses = resolver.resolve(host);
            if (addresses.isEmpty() || addresses.stream().anyMatch(this::isBlocked)) {
                throw new IllegalArgumentException("通知端点解析到受保护网络地址");
            }
            return new ValidatedEndpoint(endpoint, addresses);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("通知端点主机无法解析", exception);
        }
    }

    private URI parse(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank() || endpointUrl.length() > 2_048) {
            throw new IllegalArgumentException("通知端点地址不能为空且长度不能超过 2048");
        }
        try {
            URI endpoint = new URI(endpointUrl.strip());
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("通知端点必须是无用户信息和片段的完整 HTTPS 地址");
            }
            return endpoint;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("通知端点地址格式不合法", exception);
        }
    }

    private String normalizeHost(String source) {
        if (source == null || source.isBlank() || source.contains("*") || source.contains(":")
                || source.matches("^[0-9.]+$")) {
            throw new IllegalArgumentException("通知端点白名单只允许精确 DNS 主机名");
        }
        String host;
        try {
            host = IDN.toASCII(source.strip(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("通知端点主机名格式不合法", exception);
        }
        if (!DNS_NAME.matcher(host).matches()) {
            throw new IllegalArgumentException("通知端点白名单只允许精确 DNS 主机名");
        }
        return host;
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first == 100 && second >= 64 && second <= 127
                    || first == 192 && second == 0
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 224;
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc
                || Arrays.equals(bytes, new byte[bytes.length]);
    }

    /**
     * 安全策略校验后携带固定解析地址的端点，传输层不得再次查询 DNS。
     *
     * @param uri 原始主机名 HTTPS 地址，用于 TLS SNI 和证书主机名校验
     * @param addresses 策略校验通过的固定公网地址
     */
    public record ValidatedEndpoint(URI uri, List<InetAddress> addresses) {
        /** 校验并冻结端点快照。 */
        public ValidatedEndpoint {
            Objects.requireNonNull(uri, "通知端点 URI 不能为空");
            addresses = List.copyOf(Objects.requireNonNull(
                    addresses, "通知端点固定地址不能为空"));
            if (uri.getHost() == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("通知端点固定地址不完整");
            }
        }

        /** 返回不包含路径和查询参数的安全日志摘要。 */
        @Override
        public String toString() {
            return "ValidatedEndpoint[scheme=" + uri.getScheme()
                    + ", host=" + uri.getHost()
                    + ", port=" + uri.getPort()
                    + ", addressCount=" + addresses.size() + "]";
        }
    }
}
