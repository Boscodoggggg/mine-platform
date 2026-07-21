package com.bosco.mine.server.bootstrap.security;

import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/**
 * 在全新数据库中从外部 secret 创建唯一的初始超级管理员。
 */
@Component
public final class BootstrapAdminInitializer implements ApplicationRunner {

    private final MineSecurityProperties properties;
    private final SystemAuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 创建初始管理员初始化器。
     *
     * @param properties      平台安全配置
     * @param authMapper      系统认证数据访问接口
     * @param passwordEncoder 密码编码器
     */
    public BootstrapAdminInitializer(
            MineSecurityProperties properties,
            SystemAuthMapper authMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 数据库迁移完成后按配置执行一次幂等初始化。
     *
     * @param arguments 应用启动参数
     */
    @Override
    public void run(ApplicationArguments arguments) {
        MineSecurityProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (!admin.enabled()) {
            return;
        }
        validateAdminConfiguration(admin);
        if (authMapper.findUserById(admin.id()).isPresent()) {
            return;
        }
        if (authMapper.countUsers() > 0) {
            throw new IllegalStateException("数据库已有用户，但配置的超级管理员不存在，拒绝自动创建");
        }
        String initialPassword = readInitialPassword(admin);
        authMapper.insertBootstrapAdmin(
                admin.id(),
                admin.username(),
                passwordEncoder.encode(initialPassword),
                admin.displayName()
        );
    }

    private void validateAdminConfiguration(MineSecurityProperties.BootstrapAdmin admin) {
        if (admin.id() == null
                || isBlank(admin.username())
                || isBlank(admin.displayName())
                || admin.passwordFile() == null) {
            throw new IllegalStateException("初始超级管理员配置不完整");
        }
    }

    private String readInitialPassword(MineSecurityProperties.BootstrapAdmin admin) {
        try {
            String password = Files.readString(admin.passwordFile()).strip();
            int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (passwordBytes < 12 || passwordBytes > 72) {
                throw new IllegalStateException("初始超级管理员密码的 UTF-8 编码必须为 12 到 72 字节");
            }
            return password;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取初始超级管理员密码 secret 文件", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
