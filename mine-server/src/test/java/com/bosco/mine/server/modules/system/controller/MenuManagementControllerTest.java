package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.system.service.MenuManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验菜单管理接口的批量删除请求约束。
 */
class MenuManagementControllerTest {

    private MenuManagementService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建菜单管理接口测试环境() {
        service = mock(MenuManagementService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MenuManagementController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 应支持原子批量删除菜单节点() throws Exception {
        UUID directoryId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/system/menus/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":["%s","%s"]}
                                """.formatted(directoryId, menuId)))
                .andExpect(status().isNoContent());

        verify(service).deleteMenus(List.of(directoryId, menuId));
    }

    @Test
    void 应拒绝空的批量删除请求() throws Exception {
        mockMvc.perform(post("/api/v1/system/menus/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
