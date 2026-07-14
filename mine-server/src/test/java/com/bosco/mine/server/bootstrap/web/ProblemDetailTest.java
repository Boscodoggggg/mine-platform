package com.bosco.mine.server.bootstrap.web;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(jsonPath("$.code").value("DEVICE-400-001"))
                .andExpect(jsonPath("$.message").value("设备标识不合法"))
                .andExpect(jsonPath("$.traceId", matchesPattern("^[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.details.violations[0].field").value("deviceId"));

        assertThatMdcHasBeenCleared();
    }

    @Test
    void 应从标准错误码解析HTTP状态() throws Exception {
        for (int httpStatus : new int[]{401, 403, 404, 409, 422}) {
            mockMvc.perform(get("/test/status/{status}", httpStatus))
                    .andExpect(status().is(httpStatus))
                    .andExpect(jsonPath("$.code").value("TEST-" + httpStatus + "-001"));
        }
    }

    @Test
    void 五百错误不得泄露内部消息和详情() throws Exception {
        mockMvc.perform(get("/test/status/500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("TEST-500-001"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(content().string(not(containsString("敏感内部消息"))));
    }

    @Test
    void 应把JSON解析错误保持为四百状态() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST-400-001"))
                .andExpect(jsonPath("$.message").value("请求内容不合法"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 应透传合法链路标识并替换非法值() throws Exception {
        mockMvc.perform(get("/test/failure")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "upstream-trace-001"))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "upstream-trace-001"))
                .andExpect(jsonPath("$.traceId").value("upstream-trace-001"));

        mockMvc.perform(get("/test/failure")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "x".repeat(65)))
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER,
                        matchesPattern("^[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.traceId", matchesPattern("^[0-9a-f-]{36}$")));

        assertThatMdcHasBeenCleared();
    }

    /**
     * 仅用于触发统一异常处理器的测试控制器。
     */
    @RestController
    static final class FailingController {

        @GetMapping("/test/failure")
        void fail() {
            throw new MineException(
                    new ErrorCode("DEVICE-400-001", "设备标识不合法"),
                    Map.of("violations", java.util.List.of(
                            Map.of("field", "deviceId", "reason", "格式不正确")
                    ))
            );
        }

        @GetMapping("/test/status/{status}")
        void status(@PathVariable int status) {
            throw new MineException(
                    new ErrorCode("TEST-" + status + "-001", "敏感内部消息"),
                    Map.of("secret", "database-password")
            );
        }

        @PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody TestBody body) {
            // 请求体由 Spring MVC 解析成功即满足此测试端点用途。
        }
    }

    /**
     * 用于触发 JSON 请求体解析的测试载荷。
     *
     * @param value 测试值
     */
    record TestBody(String value) {
    }

    private void assertThatMdcHasBeenCleared() {
        org.assertj.core.api.Assertions.assertThat(MDC.get("traceId")).isNull();
    }
}
