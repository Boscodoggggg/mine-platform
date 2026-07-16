package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileRequest;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.service.DeviceConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验设备接入配置与分层诊断接口契约。
 */
class DeviceConnectionControllerTest {

    private DeviceConnectionService service;
    private MockMvc mockMvc;
    private UUID deviceId;

    @BeforeEach
    void 创建设备接入接口测试环境() {
        service = mock(DeviceConnectionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceConnectionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
        deviceId = UUID.randomUUID();
    }

    @Test
    void 应查询和保存不含凭据的设备接入配置() throws Exception {
        when(service.get(deviceId)).thenReturn(profile());
        when(service.save(eq(deviceId), any(DeviceConnectionProfileRequest.class))).thenReturn(profile());

        mockMvc.perform(get("/api/v1/devices/{id}/connection-profile", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("mqtt.mine.local"))
                .andExpect(jsonPath("$.secret").doesNotExist());

        mockMvc.perform(put("/api/v1/devices/{id}/connection-profile", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectionMode":"DEVICE_TO_PLATFORM_MQTT",
                                  "host":"mqtt.mine.local",
                                  "port":8883,
                                  "tlsEnabled":true,
                                  "tlsServerName":"mqtt.mine.local",
                                  "connectTimeoutMillis":5000,
                                  "keepAliveSeconds":60,
                                  "offlineThresholdSeconds":180,
                                  "version":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionMode").value("DEVICE_TO_PLATFORM_MQTT"));
    }

    @Test
    void 空主机和非法端口应返回参数错误() throws Exception {
        mockMvc.perform(put("/api/v1/devices/{id}/connection-profile", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "connectionMode":"DEVICE_TO_PLATFORM_MQTT",
                                  "host":" ",
                                  "port":70000,
                                  "tlsEnabled":true,
                                  "connectTimeoutMillis":5000,
                                  "keepAliveSeconds":60,
                                  "offlineThresholdSeconds":180,
                                  "version":0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 应返回分层诊断结果() throws Exception {
        when(service.test(deviceId)).thenReturn(new DeviceConnectionTestResponse(
                deviceId, "PARTIAL", Instant.parse("2026-07-16T00:00:00Z"), 12,
                "网络端点检查通过，仍需设备首帧确认",
                List.of(new DeviceConnectionTestResponse.Stage("DNS", "PASSED", "DNS 解析成功", 2))
        ));

        mockMvc.perform(post("/api/v1/devices/{id}/connection-profile/test", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.stages[0].stage").value("DNS"));
    }

    @Test
    void 三个接口必须声明独立的最小权限() throws Exception {
        assertPermission("get", "device:connection:view", UUID.class);
        assertPermission("save", "device:connection:update", UUID.class, DeviceConnectionProfileRequest.class);
        assertPermission("test", "device:connection:test", UUID.class);
    }

    private DeviceConnectionProfileResponse profile() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new DeviceConnectionProfileResponse(
                UUID.randomUUID(), deviceId, "DEVICE_TO_PLATFORM_MQTT", "mqtt.mine.local", 8883,
                true, "mqtt.mine.local", 5000, 60, 180,
                "NOT_TESTED", null, null, null, List.of(), now, now, 0
        );
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = DeviceConnectionController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
