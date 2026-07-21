package com.bosco.mine.simulator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证无认证管理页面不会误绑定到局域网。
 */
class LocalBindingGuardTest {

    @Test
    void 应允许IPv4与IPv6回环地址() {
        assertThatCode(() -> new LocalBindingGuard("127.0.0.1", false)).doesNotThrowAnyException();
        assertThatCode(() -> new LocalBindingGuard("::1", false)).doesNotThrowAnyException();
        assertThatCode(() -> new LocalBindingGuard("localhost", false)).doesNotThrowAnyException();
    }

    @Test
    void 应拒绝任意地址与局域网地址() {
        assertThatThrownBy(() -> new LocalBindingGuard("0.0.0.0", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅允许绑定本机回环地址");
        assertThatThrownBy(() -> new LocalBindingGuard("192.168.1.20", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅允许绑定本机回环地址");
    }

    @Test
    void 容器部署层明确限制宿主端口时可允许非回环监听() {
        assertThatCode(() -> new LocalBindingGuard("0.0.0.0", true)).doesNotThrowAnyException();
    }
}
