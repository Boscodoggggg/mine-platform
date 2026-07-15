package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.device.dto.DeviceCredentialResponse;
import com.bosco.mine.server.modules.device.service.DeviceCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验设备凭据接口的一次性响应、版本参数和权限声明。
 */
class DeviceCredentialControllerTest {

    private DeviceCredentialService credentialService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建设备凭据接口测试环境() {
        credentialService = mock(DeviceCredentialService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DeviceCredentialController(credentialService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 签发应返回二零一和一次性密钥() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(credentialService.issue(deviceId)).thenReturn(credential(credentialId, deviceId, 0));

        mockMvc.perform(post("/api/v1/devices/{id}/credentials", deviceId))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "/api/v1/device-credentials/" + credentialId
                ))
                .andExpect(jsonPath("$.secret").value("one-time-secret"))
                .andExpect(jsonPath("$.clientId").value("device-client"));
    }

    @Test
    void 轮换和吊销必须携带版本() throws Exception {
        UUID credentialId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(credentialService.rotate(credentialId, 3))
                .thenReturn(credential(credentialId, deviceId, 4));

        mockMvc.perform(post("/api/v1/device-credentials/{id}/rotate", credentialId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(delete("/api/v1/device-credentials/{id}", credentialId)
                        .param("version", "4"))
                .andExpect(status().isNoContent());

        verify(credentialService).rotate(credentialId, 3);
        verify(credentialService).revoke(credentialId, 4);
    }

    @Test
    void 三个敏感接口必须声明独立权限() throws Exception {
        assertPermission("issue", "device:credential:create", UUID.class);
        assertPermission(
                "rotate", "device:credential:rotate",
                UUID.class, com.bosco.mine.server.modules.device.dto.CredentialRotateRequest.class
        );
        assertPermission("revoke", "device:credential:revoke", UUID.class, long.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = DeviceCredentialController.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(SaCheckPermission.class).value()).containsExactly(permission);
    }

    private DeviceCredentialResponse credential(UUID id, UUID deviceId, long version) {
        return new DeviceCredentialResponse(
                id,
                deviceId,
                "device-client",
                "device-client",
                "one-time-secret",
                "mine/v1/truck/device-client",
                "mine/v1/truck/device-client/up",
                "mine/v1/truck/device-client/down",
                version
        );
    }
}
