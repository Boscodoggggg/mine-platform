package com.bosco.mine.server.modules.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.notification.dto.NotificationTemplateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验通知模板接口的精确查看和编辑权限。
 */
class NotificationTemplateControllerTest {

    @Test
    void 查询和写入模板接口必须使用独立权限() throws Exception {
        assertPermission("variables", "notification:template:view");
        assertPermission("list", "notification:template:view");
        assertPermission("get", "notification:template:view", UUID.class);
        assertPermission("create", "notification:template:edit",
                NotificationTemplateRequest.class);
        assertPermission("update", "notification:template:edit",
                UUID.class, NotificationTemplateRequest.class);
    }

    private void assertPermission(
            String methodName,
            String expected,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = NotificationTemplateController.class.getMethod(
                methodName, parameterTypes);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.value())).containsExactly(expected);
    }
}
