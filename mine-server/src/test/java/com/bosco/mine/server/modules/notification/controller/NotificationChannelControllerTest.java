package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelSecretsResponse;
import com.bosco.mine.server.modules.notification.service.NotificationChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通知渠道接口的精确权限和服务委托。
 */
class NotificationChannelControllerTest {

    @Test
    void 查询与写入接口必须使用独立权限() throws Exception {
        assertPermission("providers", "notification:channel:view");
        assertPermission("list", "notification:channel:view");
        assertPermission("get", "notification:channel:view", UUID.class);
        assertPermission("secrets", "notification:channel:secret:view", UUID.class);
        assertPermission("create", "notification:channel:edit", NotificationChannelRequest.class);
        assertPermission("update", "notification:channel:edit",
                UUID.class, NotificationChannelRequest.class);
    }

    @Test
    void 控制器必须把查询委托给服务且不自行读取密文() {
        NotificationChannelService service = mock(NotificationChannelService.class);
        NotificationChannelController controller = new NotificationChannelController(service);
        UUID channelId = UUID.randomUUID();

        controller.providers();
        controller.list();
        controller.get(channelId);

        verify(service).providers();
        verify(service).list();
        verify(service).get(channelId);
    }

    @Test
    void 敏感凭据响应必须禁止浏览器和代理缓存() {
        NotificationChannelService service = mock(NotificationChannelService.class);
        NotificationChannelController controller = new NotificationChannelController(service);
        UUID channelId = UUID.randomUUID();
        NotificationChannelSecretsResponse secrets = new NotificationChannelSecretsResponse(
                channelId, "smtp", Map.of("password", "mail-secret"), 2,
                Instant.parse("2026-07-21T00:00:00Z"));
        when(service.readSecrets(channelId)).thenReturn(secrets);

        var response = controller.secrets(channelId);

        assertThat(response.getBody()).isSameAs(secrets);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        verify(service).readSecrets(channelId);
    }

    private void assertPermission(
            String methodName,
            String expected,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = NotificationChannelController.class.getMethod(methodName, parameterTypes);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.value())).containsExactly(expected);
    }
}
