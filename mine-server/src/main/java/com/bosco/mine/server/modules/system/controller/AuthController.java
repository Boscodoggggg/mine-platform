package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.modules.system.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供管理端登录、退出和当前用户查询接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {

    private final AuthService authService;

    /**
     * 创建认证控制器。
     *
     * @param authService 认证服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 使用用户名密码创建管理端会话。
     *
     * @param request 登录请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        return new LoginResponse(
                result.tokenName(),
                result.tokenValue(),
                UserResponse.from(result.user())
        );
    }

    /**
     * 获取当前登录用户资料。
     *
     * @return 当前用户资料
     */
    @GetMapping("/me")
    public UserResponse me() {
        return UserResponse.from(authService.currentUser());
    }

    /**
     * 退出当前会话。
     *
     * @return 空响应
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    /**
     * 登录请求。
     *
     * @param username 登录名
     * @param password 密码
     */
    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 256) String password
    ) {
    }

    /**
     * 登录响应。
     *
     * @param tokenName  令牌请求头名称
     * @param tokenValue 令牌值
     * @param user       用户资料
     */
    public record LoginResponse(String tokenName, String tokenValue, UserResponse user) {
    }

    /**
     * 可返回给管理端的用户资料。
     *
     * @param id                     用户标识
     * @param username               登录名
     * @param displayName            展示名称
     * @param passwordChangeRequired 是否必须修改初始密码
     */
    public record UserResponse(
            UUID id,
            String username,
            String displayName,
            boolean passwordChangeRequired
    ) {

        private static UserResponse from(AuthService.UserProfile profile) {
            return new UserResponse(
                    profile.id(),
                    profile.username(),
                    profile.displayName(),
                    profile.passwordChangeRequired()
            );
        }
    }
}
