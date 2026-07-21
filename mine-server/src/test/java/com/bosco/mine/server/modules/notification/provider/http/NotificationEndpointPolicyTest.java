package com.bosco.mine.server.modules.notification.provider.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEndpointPolicyTest {

    @Test
    void 只允许Https和显式主机白名单() throws Exception {
        NotificationEndpointPolicy policy = new NotificationEndpointPolicy(
                host -> List.of(InetAddress.getByName("8.8.8.8")));

        NotificationEndpointPolicy.ValidatedEndpoint endpoint = policy.validate(
                "https://hooks.example.com/mine", Set.of("hooks.example.com"));

        assertThat(endpoint.uri()).isEqualTo(java.net.URI.create("https://hooks.example.com/mine"));
        assertThat(endpoint.addresses()).extracting(InetAddress::getHostAddress)
                .containsExactly("8.8.8.8");
        assertThatThrownBy(() -> policy.validate(
                "http://hooks.example.com/mine", Set.of("hooks.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(
                "https://evil.example.com/mine", Set.of("hooks.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 阻断回环私网链路本地和云元数据地址() throws Exception {
        NotificationEndpointPolicy policy = new NotificationEndpointPolicy(host -> switch (host) {
            case "loop.example.com" -> List.of(InetAddress.getByName("127.0.0.1"));
            case "private.example.com" -> List.of(InetAddress.getByName("10.10.1.8"));
            case "metadata.example.com" -> List.of(InetAddress.getByName("169.254.169.254"));
            default -> List.of(InetAddress.getByName("8.8.8.8"));
        });

        for (String host : List.of("loop.example.com", "private.example.com", "metadata.example.com")) {
            assertThatThrownBy(() -> policy.validate("https://" + host + "/hook", Set.of(host)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 拒绝用户信息IP字面量和通配白名单() throws Exception {
        NotificationEndpointPolicy policy = new NotificationEndpointPolicy(
                host -> List.of(InetAddress.getByName("8.8.8.8")));

        assertThatThrownBy(() -> policy.validate(
                "https://user:pass@hooks.example.com/hook", Set.of("hooks.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://8.8.8.8/hook", Set.of("8.8.8.8")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(
                "https://hooks.example.com/hook", Set.of("*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
