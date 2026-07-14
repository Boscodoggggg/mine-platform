package com.bosco.mine.server.bootstrap.web;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验平台可识别异常被转换为稳定、可追踪的 HTTP 错误契约。
 */
class ProblemDetailTest {

    private MockMvc mockMvc;

    @BeforeEach
    void 创建独立的Web测试环境() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 应返回错误码详情和链路标识() throws Exception {
        mockMvc.perform(get("/test/failure"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.code").value("DEVICE_IDENTIFIER_INVALID"))
                .andExpect(jsonPath("$.message").value("设备标识不合法"))
                .andExpect(jsonPath("$.traceId", matchesPattern("^[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.details.field").value("deviceId"));
    }

    /**
     * 仅用于触发统一异常处理器的测试控制器。
     */
    @RestController
    static final class FailingController {

        @GetMapping("/test/failure")
        void fail() {
            throw new MineException(
                    new ErrorCode("DEVICE_IDENTIFIER_INVALID", "设备标识不合法"),
                    Map.of("field", "deviceId")
            );
        }
    }
}
