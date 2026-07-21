package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientGroupRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationRecipientTargetResponse;
import com.bosco.mine.server.modules.notification.service.NotificationRecipientService;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验通知接收对象接口的权限和敏感响应缓存边界。
 */
class NotificationRecipientControllerTest {

    @Test
    void 查询编辑和敏感目标接口必须使用独立精确权限() throws Exception {
        assertPermission("listGroups", "notification:recipient:view");
        assertPermission("getGroup", "notification:recipient:view", UUID.class);
        assertPermission("listRecipients", "notification:recipient:view", UUID.class);
        assertPermission("createGroup", "notification:recipient:edit",
                NotificationRecipientGroupRequest.class);
        assertPermission("updateGroup", "notification:recipient:edit",
                UUID.class, NotificationRecipientGroupRequest.class);
        assertPermission("createRecipient", "notification:recipient:edit",
                UUID.class, NotificationRecipientRequest.class);
        assertPermission("updateRecipient", "notification:recipient:edit",
                UUID.class, UUID.class, NotificationRecipientRequest.class);
        assertPermission("target", "notification:recipient:secret:view",
                UUID.class, UUID.class);
    }

    @Test
    void 敏感接收目标响应必须禁止浏览器和代理缓存() {
        NotificationRecipientService service = mock(NotificationRecipientService.class);
        NotificationRecipientController controller = new NotificationRecipientController(service);
        UUID groupId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        NotificationRecipientTargetResponse target = new NotificationRecipientTargetResponse(
                recipientId, "operator@example.com", 2,
                Instant.parse("2026-07-21T00:00:00Z"));
        when(service.readTarget(groupId, recipientId)).thenReturn(target);

        var response = controller.target(groupId, recipientId);

        assertThat(response.getBody()).isSameAs(target);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        verify(service).readTarget(groupId, recipientId);
    }

    private void assertPermission(
            String methodName,
            String expected,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = NotificationRecipientController.class.getMethod(
                methodName, parameterTypes);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.value())).containsExactly(expected);
    }
}
