package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.modules.system.service.NavigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验当前用户菜单、角色和按钮权限的 HTTP 契约。
 */
class MenuControllerTest {

    private NavigationService navigationService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建菜单接口测试环境() {
        navigationService = mock(NavigationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MenuController(navigationService)).build();
    }

    @Test
    void 应返回目录菜单按钮树和独立权限集合() throws Exception {
        UUID directoryId = UUID.fromString("0198aef6-2e80-7000-8000-000000000101");
        UUID menuId = UUID.fromString("0198aef6-2e80-7000-8000-000000000102");
        UUID buttonId = UUID.fromString("0198aef6-2e80-7000-8000-000000000103");
        NavigationService.MenuNode button = new NavigationService.MenuNode(
                buttonId, menuId, "查看设备", "BUTTON", null, null,
                "device:device:list", null, 1, true, null, List.of()
        );
        NavigationService.MenuNode menu = new NavigationService.MenuNode(
                menuId, directoryId, "设备台账", "MENU", "/devices", "device-list",
                null, "mdi:truck", 1, true, "mine.menu.devices", List.of(button)
        );
        NavigationService.MenuNode directory = new NavigationService.MenuNode(
                directoryId, null, "设备管理", "DIRECTORY", "/device", null,
                null, "mdi:factory", 1, true, "mine.menu.device", List.of(menu)
        );
        when(navigationService.currentNavigation()).thenReturn(new NavigationService.NavigationResult(
                List.of("DEVICE_OPERATOR"),
                List.of("device:device:list"),
                List.of(directory)
        ));

        mockMvc.perform(get("/api/v1/auth/menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("DEVICE_OPERATOR"))
                .andExpect(jsonPath("$.permissions[0]").value("device:device:list"))
                .andExpect(jsonPath("$.menus[0].type").value("DIRECTORY"))
                .andExpect(jsonPath("$.menus[0].children[0].type").value("MENU"))
                .andExpect(jsonPath("$.menus[0].children[0].componentKey").value("device-list"))
                .andExpect(jsonPath("$.menus[0].children[0].children[0].type").value("BUTTON"));
    }
}
