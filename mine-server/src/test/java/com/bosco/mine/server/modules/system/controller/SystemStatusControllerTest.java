package com.bosco.mine.server.modules.system.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验系统状态接口对部署和运维暴露稳定的版本信息。
 */
@WebMvcTest(SystemStatusController.class)
@TestPropertySource(properties = "mine.platform.version=0.1.0-test")
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 应返回服务状态和平台版本() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.1.0-test"));
    }
}
