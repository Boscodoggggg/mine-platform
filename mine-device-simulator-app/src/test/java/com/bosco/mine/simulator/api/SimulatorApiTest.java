package com.bosco.mine.simulator.api;

import com.bosco.mine.simulator.runtime.SimulatorRuntimeService;
import com.bosco.mine.simulator.transport.TransportState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.awaitility.Awaitility.await;

/**
 * 验证管理页面与设备配置接口可以随单体应用直接使用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SimulatorApiTest {

    private static final Path PROFILE_FILE = createProfileFile();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SimulatorRuntimeService runtimeService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mine.simulator.profile-file", PROFILE_FILE::toString);
    }

    @Test
    void 首页应直接由应用提供() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(new String(result.getResponse().getContentAsByteArray(),
                        StandardCharsets.UTF_8)).contains(
                                "矿山设备模拟台", "addDeviceButton", "profileForm",
                                "credentialDialog", "activityList"));
    }

    @Test
    void 应新增查询并删除设备配置() throws Exception {
        String body = """
                {
                  "id":"api-device-1","name":"接口测试矿卡","transportMode":"MQTT",
                  "host":"127.0.0.1","port":1883,"tlsEnabled":false,
                  "productKey":"truck","deviceCode":"truck-001","clientId":"client-001",
                  "username":"device","publishTopic":"mine/test/up","payloadFormat":"JSON",
                  "payloadTemplate":"{\\\"sequence\\\":${sequence}}","intervalMillis":1000,"qos":1
                }
                """;

        mockMvc.perform(post("/api/profiles").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("api-device-1"));
        mockMvc.perform(get("/api/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("接口测试矿卡"));
        mockMvc.perform(delete("/api/profiles/api-device-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void 应通过操作接口启动单发断连重连并停止TCP设备且不泄露密码() throws Exception {
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        String body = """
                {
                  "id":"action-device","name":"操作测试服务端","transportMode":"TCP_SERVER",
                  "host":"127.0.0.1","port":%d,"tlsEnabled":false,
                  "productKey":"plc","deviceCode":"plc-001","clientId":"","username":"",
                  "publishTopic":"","payloadFormat":"HEX","payloadTemplate":"68 16",
                  "intervalMillis":1000,"qos":0
                }
                """.formatted(port);
        mockMvc.perform(post("/api/profiles").contentType("application/json").content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/profiles/action-device/actions/start")
                        .contentType("application/json").content("{\"password\":\"top-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("top-secret"))));
        try (Socket platform = new Socket("127.0.0.1", port)) {
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> runtimeService.find("action-device").state() == TransportState.CONNECTED);
            mockMvc.perform(post("/api/profiles/action-device/actions/send"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sentCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
            assertThat(platform.getInputStream().readNBytes(2)).containsExactly(0x68, 0x16);
            mockMvc.perform(post("/api/profiles/action-device/actions/disconnect"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("DISCONNECTED"));
        }
        mockMvc.perform(post("/api/profiles/action-device/actions/reconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING"));
        mockMvc.perform(post("/api/profiles/action-device/actions/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
        mockMvc.perform(delete("/api/profiles/action-device"))
                .andExpect(status().isNoContent());
    }

    private static Path createProfileFile() {
        try {
            return Files.createTempDirectory("simulator-api-").resolve("profiles.yaml");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
