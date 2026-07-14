package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.modules.system.service.AuthService;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.regex.Pattern;

/**
 * 提供管理端登录、退出和当前用户查询接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

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
     * @param servletRequest 当前 HTTP 请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthService.LoginResult result = authService.login(
                request.username(),
                request.password(),
                auditContext(servletRequest)
        );
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
     * 修改当前用户密码。成功后注销该用户的全部旧会话，调用方必须重新登录。
     *
     * @param request 修改密码请求
     * @param servletRequest 当前 HTTP 请求
     * @return 空响应
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest servletRequest
    ) {
        authService.changePassword(
                request.currentPassword(),
                request.newPassword(),
                auditContext(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    /**
     * 退出当前会话。
     *
     * @param servletRequest 当前 HTTP 请求
     * @return 空响应
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest servletRequest) {
        authService.logout(auditContext(servletRequest));
        return ResponseEntity.noContent().build();
    }

    private AuthService.AuditContext auditContext(HttpServletRequest request) {
        Object traceAttribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String traceId = traceAttribute instanceof String value && !value.isBlank()
                ? value
                : UUID.randomUUID().toString();
        String requestHeader = request.getHeader(REQUEST_ID_HEADER);
        String requestId = requestHeader != null && SAFE_REQUEST_ID.matcher(requestHeader).matches()
                ? requestHeader
                : traceId;
        return new AuthService.AuditContext(
                requestId,
                traceId,
                request.getRemoteAddr(),
                truncate(request.getHeader("User-Agent"), 512)
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
     * 修改密码请求。
     *
     * @param currentPassword 当前密码
     * @param newPassword     新密码，BCrypt 限制其 UTF-8 编码不得超过 72 字节
     */
    public record PasswordChangeRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword
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
