package com.bosco.mine.server.bootstrap.websocket;

import cn.dev33.satoken.stp.StpLogic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验实时通道使用 Sa-Token 的现有登录态和权限模型。
 */
class SaTokenRealtimeAuthenticatorTest {

    @Test
    void 有效令牌应返回用户和权限快照() {
        StpLogic stpLogic = mock(StpLogic.class);
        UUID userId = UUID.fromString("0198aef6-2e80-7000-8000-000000000001");
        when(stpLogic.isValidToken("valid-token")).thenReturn(true);
        when(stpLogic.getLoginIdByToken("valid-token")).thenReturn(userId.toString());
        when(stpLogic.getPermissionList(userId.toString()))
                .thenReturn(List.of("device:telemetry:view"));
        SaTokenRealtimeAuthenticator authenticator = new SaTokenRealtimeAuthenticator(stpLogic);

        RealtimePrincipal principal = authenticator.authenticate("valid-token");

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.permissions()).containsExactly("device:telemetry:view");
    }

    @Test
    void 无效令牌不得建立实时用户() {
        StpLogic stpLogic = mock(StpLogic.class);
        when(stpLogic.isValidToken("expired-token")).thenReturn(false);
        SaTokenRealtimeAuthenticator authenticator = new SaTokenRealtimeAuthenticator(stpLogic);

        assertThatThrownBy(() -> authenticator.authenticate("expired-token"))
                .isInstanceOf(RealtimeAuthenticationException.class)
                .hasMessageContaining("无效或已过期");
    }
}
