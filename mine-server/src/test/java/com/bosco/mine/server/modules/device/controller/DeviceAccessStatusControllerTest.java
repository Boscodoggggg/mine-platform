package com.bosco.mine.server.modules.device.controller;

import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.device.dto.DeviceAccessStatusResponse;
import com.bosco.mine.server.modules.device.dto.DeviceAccessTestResponse;
import com.bosco.mine.server.modules.device.service.DeviceAccessStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验设备持续接入状态和手动检查接口。
 */
class DeviceAccessStatusControllerTest {

    private DeviceAccessStatusService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建设备接入状态控制器测试环境() {
        service = mock(DeviceAccessStatusService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceAccessStatusController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 应批量返回设备持续接入状态() throws Exception {
        UUID deviceId = UUID.randomUUID();
        when(service.find(List.of(deviceId))).thenReturn(List.of(new DeviceAccessStatusResponse(
                deviceId, "ONLINE", Instant.parse("2026-07-16T07:00:00Z"),
                Instant.parse("2026-07-16T07:59:30Z"), 180, "ACCESS_ONLINE"
        )));

        mockMvc.perform(get("/api/v1/devices/access-status")
                        .param("deviceIds", deviceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ONLINE"))
                .andExpect(jsonPath("$[0].message").value("ACCESS_ONLINE"));
    }

    @Test
    void 应允许接入工程师手动执行四层检查() throws Exception {
        UUID deviceId = UUID.randomUUID();
        when(service.test(deviceId)).thenReturn(new DeviceAccessTestResponse(
                deviceId, "TRANSPORT_ONLY", Instant.parse("2026-07-16T08:00:00Z"), 12,
                "TRANSPORT_ONLY", List.of(new DeviceAccessTestResponse.Stage(
                        "TRANSPORT", "PASSED", "TRANSPORT_CONNECTED",
                        Instant.parse("2026-07-16T07:59:55Z"), 0
                ))
        ));

        mockMvc.perform(post("/api/v1/devices/{id}/access-status/test", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRANSPORT_ONLY"))
                .andExpect(jsonPath("$.stages[0].layer").value("TRANSPORT"));
    }
}
