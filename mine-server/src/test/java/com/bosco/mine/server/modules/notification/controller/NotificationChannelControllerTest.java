package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelRequest;
import com.bosco.mine.server.modules.notification.service.NotificationChannelService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验通知渠道接口的精确权限和服务委托。
 */
class NotificationChannelControllerTest {

    @Test
    void 查询与写入接口必须使用独立权限() throws Exception {
        assertPermission("providers", "notification:channel:view");
        assertPermission("list", "notification:channel:view");
        assertPermission("get", "notification:channel:view", UUID.class);
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
