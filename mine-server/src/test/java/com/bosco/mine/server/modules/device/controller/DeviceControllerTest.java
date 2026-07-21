package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.dto.ProductResponse;
import com.bosco.mine.server.modules.device.service.DeviceCommandService;
import com.bosco.mine.server.modules.device.service.DeviceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验产品和设备管理接口的请求契约、错误映射与权限声明。
 */
class DeviceControllerTest {

    private DeviceCommandService commandService;
    private DeviceQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建设备管理接口测试环境() {
        commandService = mock(DeviceCommandService.class);
        queryService = mock(DeviceQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceController(commandService, queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 创建产品应返回二零一和资源地址() throws Exception {
        UUID productId = UUID.randomUUID();
        when(commandService.createProduct(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(product(productId));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productKey":"haul-truck",
                                  "name":"矿卡终端",
                                  "deviceType":"VEHICLE",
                                  "status":"ACTIVE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/products/" + productId))
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.productKey").value("haul-truck"));
    }

    @Test
    void 创建设备应返回二零一且不暴露凭据() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(commandService.createDevice(any(UUID.class), anyString(), anyString(), anyString()))
                .thenReturn(device(deviceId, productId));

        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "deviceCode":"truck-001",
                                  "name":"一号矿卡",
                                  "status":"ENABLED"
                                }
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/devices/" + deviceId))
                .andExpect(jsonPath("$.deviceCode").value("truck-001"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void 重复产品编码应返回稳定的冲突错误() throws Exception {
        doThrow(new MineException(
                new ErrorCode("DEVICE-409-001", "产品编码已存在"),
                Map.of("productKey", "haul-truck")
        )).when(commandService).createProduct(anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productKey":"haul-truck",
                                  "name":"矿卡终端",
                                  "deviceType":"VEHICLE",
                                  "status":"ACTIVE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE-409-001"))
                .andExpect(jsonPath("$.details.productKey").value("haul-truck"));
    }

    @Test
    void 产品和设备列表应返回标准分页对象() throws Exception {
        UUID productId = UUID.randomUUID();
        when(queryService.findProducts("矿卡", "ACTIVE", 1, 20))
                .thenReturn(new PageResponse<>(List.of(product(productId)), 1, 20, 1));
        when(queryService.findDevices(productId, "truck", "ENABLED", 1, 20))
                .thenReturn(new PageResponse<>(List.of(device(UUID.randomUUID(), productId)), 1, 20, 1));

        mockMvc.perform(get("/api/v1/products")
                        .param("keyword", "矿卡")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productKey").value("haul-truck"))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/v1/devices")
                        .param("productId", productId.toString())
                        .param("keyword", "truck")
                        .param("status", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deviceCode").value("truck-001"));
    }

    @Test
    void 归档设备应返回无内容响应() throws Exception {
        UUID deviceId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/devices/{id}/archive", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isNoContent());

        verify(commandService).archiveDevice(deviceId, 3);
    }

    @Test
    void 每个设备管理入口都应声明明确权限() throws Exception {
        assertPermission("findProducts", "device:product:view",
                String.class, String.class, int.class, int.class);
        assertPermission("createProduct", "device:product:create",
                com.bosco.mine.server.modules.device.dto.ProductCreateRequest.class);
        assertPermission("archiveProduct", "device:product:archive",
                UUID.class, DeviceController.ArchiveRequest.class);
        assertPermission("findDevices", "device:device:view",
                UUID.class, String.class, String.class, int.class, int.class);
        assertPermission("createDevice", "device:device:create",
                com.bosco.mine.server.modules.device.dto.DeviceCreateRequest.class);
        assertPermission("archiveDevice", "device:device:archive",
                UUID.class, DeviceController.ArchiveRequest.class);
    }

    private ProductResponse product(UUID productId) {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        return new ProductResponse(
                productId, "haul-truck", "矿卡终端", "VEHICLE", "ACTIVE",
                null, now, now, 0
        );
    }

    private DeviceResponse device(UUID deviceId, UUID productId) {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        return new DeviceResponse(
                deviceId, productId, "truck-001", "一号矿卡", "ENABLED",
                null, now, now, 0
        );
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = DeviceController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
