package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyActionRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyPublishRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationPolicyRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验通知升级策略接口的查看、编辑和发布权限。
 */
class NotificationPolicyControllerTest {

    @Test
    void 查询编辑和发布动作必须使用独立精确权限() throws Exception {
        assertPermission("list", "notification:policy:view");
        assertPermission("get", "notification:policy:view", UUID.class);
        assertPermission("create", "notification:policy:edit", NotificationPolicyRequest.class);
        assertPermission("updateDraft", "notification:policy:edit",
                UUID.class, NotificationPolicyRequest.class);
        assertPermission("createDraft", "notification:policy:edit",
                UUID.class, NotificationPolicyActionRequest.class);
        assertPermission("archive", "notification:policy:edit",
                UUID.class, NotificationPolicyActionRequest.class);
        assertPermission("publish", "notification:policy:publish",
                UUID.class, NotificationPolicyPublishRequest.class);
    }

    private void assertPermission(
            String methodName,
            String expected,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = NotificationPolicyController.class.getMethod(methodName, parameterTypes);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.value())).containsExactly(expected);
    }
}
