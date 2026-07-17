package com.bosco.mine.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证独立设备模拟系统可以加载 Spring Boot 上下文。
 */
@SpringBootTest(
        classes = SimulatorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SimulatorApplicationTest {

    private static final Path PROFILE_FILE = createProfileFile();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mine.simulator.profile-file", PROFILE_FILE::toString);
    }

    @Test
    void 应加载独立模拟器上下文() {
    }

    private static Path createProfileFile() {
        try {
            return Files.createTempDirectory("simulator-context-").resolve("profiles.yaml");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
