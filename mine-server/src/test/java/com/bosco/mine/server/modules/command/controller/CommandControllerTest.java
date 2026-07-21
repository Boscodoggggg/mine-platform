package com.bosco.mine.server.modules.command.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.command.dto.CommandResponse;
import com.bosco.mine.server.modules.command.dto.CommandTargetResponse;
import com.bosco.mine.server.modules.command.service.CommandQueryService;
import com.bosco.mine.server.modules.command.service.CommandService;
import com.bosco.mine.server.modules.command.template.CommandTemplateService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验命令中心接口的幂等头、响应结构和权限声明。
 */
class CommandControllerTest {
    private CommandService commandService;
    private CommandQueryService queryService;
    private CommandTemplateService templateService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建命令接口测试环境() {
        commandService = mock(CommandService.class);
        queryService = mock(CommandQueryService.class);
        templateService = mock(CommandTemplateService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CommandController(commandService, queryService, templateService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 下发命令应要求幂等键并返回资源地址() throws Exception {
        CommandResponse command = command("QUEUED");
        when(commandService.createCurrentUserCommand(
                any(UUID.class), anyString(), any(), any(), any(), anyString(), anyInt(), anyInt(), anyString()
        )).thenReturn(command);

        mockMvc.perform(post("/api/v1/commands")
                        .header("Idempotency-Key", "request-command-001")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace-command-api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId":"%s",
                                  "templateKey":"QUERY_STATUS",
                                  "parameters":{},
                                  "ttlSeconds":300,
                                  "timeoutSeconds":30
                                }
                                """.formatted(command.deviceId())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/commands/" + command.id()))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(commandService).createCurrentUserCommand(
                command.deviceId(), "QUERY_STATUS", Map.of(), null, null, "request-command-001",
                300, 30, "trace-command-api"
        );
    }

    @Test
    void 缺少幂等键应返回参数错误() throws Exception {
        mockMvc.perform(post("/api/v1/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId":"019f64e5-a000-7000-8000-000000000020",
                                  "templateKey":"QUERY_STATUS",
                                  "parameters":{},
                                  "ttlSeconds":300,
                                  "timeoutSeconds":30
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 临时十六进制入口应要求原因并返回命令记录() throws Exception {
        CommandResponse command = command("QUEUED");
        when(commandService.createCurrentUserRawHexCommand(
                any(UUID.class), anyString(), anyString(), anyString(), anyInt(), anyString()
        )).thenReturn(command);

        mockMvc.perform(post("/api/v1/commands/raw-hex")
                        .header("Idempotency-Key", "request-raw-001")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace-command-raw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId":"%s",
                                  "rawPayloadHex":"0xAA BB 01",
                                  "reason":"现场协议联调",
                                  "ttlSeconds":300
                                }
                                """.formatted(command.deviceId())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/commands/" + command.id()));

        verify(commandService).createCurrentUserRawHexCommand(
                command.deviceId(), "0xAA BB 01", "现场协议联调",
                "request-raw-001", 300, "trace-command-raw"
        );
    }

    @Test
    void 命令业务校验失败应返回稳定的四百二十二错误() throws Exception {
        when(commandService.createCurrentUserCommand(
                any(UUID.class), anyString(), any(), any(), any(), anyString(), anyInt(), anyInt(), anyString()
        )).thenThrow(new MineException(
                new ErrorCode("COMMAND-422-003", "命令参数不符合模板约束"), Map.of()
        ));

        mockMvc.perform(post("/api/v1/commands")
                        .header("Idempotency-Key", "request-command-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId":"019f64e5-a000-7000-8000-000000000020",
                                  "templateKey":"QUERY_STATUS",
                                  "parameters":{"unexpected":true},
                                  "ttlSeconds":300,
                                  "timeoutSeconds":30
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMAND-422-003"));
    }

    @Test
    void 命令列表和详情应返回完整状态时间线() throws Exception {
        CommandResponse command = command("SUCCEEDED");
        when(queryService.findCommands(command.deviceId(), "SUCCEEDED", null, 1, 20))
                .thenReturn(new PageResponse<>(List.of(command), 1, 20, 1));
        when(queryService.findById(command.id())).thenReturn(command);

        mockMvc.perform(get("/api/v1/commands")
                        .param("deviceId", command.deviceId().toString())
                        .param("status", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].completedAt").exists())
                .andExpect(jsonPath("$.items[0].reply.online").value(true));
        mockMvc.perform(get("/api/v1/commands/{id}", command.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(command.id().toString()));
    }

    @Test
    void 命令目标接口应独立于设备台账权限提供启用设备() throws Exception {
        when(queryService.findTargets(null, 1, 100)).thenReturn(new PageResponse<>(
                List.of(new CommandTargetResponse(
                        UUID.fromString("019f64e5-a000-7000-8000-000000000020"),
                        "truck-001", "一号运输车"
                )), 1, 100, 1
        ));

        mockMvc.perform(get("/api/v1/command-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deviceCode").value("truck-001"));
    }

    @Test
    void 所有命令入口都应声明明确权限() throws Exception {
        assertPermission("templates", "command:template:view", UUID.class);
        assertPermission("targets", "command:instance:view", String.class, int.class, int.class);
        assertPermission("findCommands", "command:instance:view",
                UUID.class, String.class, String.class, int.class, int.class);
        assertPermission("findCommand", "command:instance:view", UUID.class);
        assertPermission("createCommand", "command:instance:create",
                String.class, jakarta.servlet.http.HttpServletRequest.class,
                com.bosco.mine.server.modules.command.dto.CommandCreateRequest.class);
        assertPermission("createRawHexCommand", "command:raw:execute",
                String.class, jakarta.servlet.http.HttpServletRequest.class,
                CommandController.RawHexCommandRequest.class);
        assertPermission("cancelCommand", "command:instance:cancel",
                UUID.class, CommandController.CommandCancelRequest.class);
    }

    private CommandResponse command(String status) {
        Instant createdAt = Instant.parse("2026-07-15T08:00:00Z");
        return new CommandResponse(
                UUID.fromString("019f64e5-a000-7000-8000-000000000010"),
                UUID.fromString("019f64e5-a000-7000-8000-000000000020"),
                "QUERY_STATUS", 1, Map.of(), null, null, null, null,
                "BUILTIN_TEMPLATE", "MQTT_JSON", null, null, true,
                "LOW", status, 300, 30,
                "request-command-001", createdAt, createdAt, createdAt,
                createdAt.plusSeconds(1), createdAt.plusSeconds(2), createdAt.plusSeconds(3),
                createdAt.plusSeconds(300), null, Map.of("online", true), false, 4
        );
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = CommandController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
