package com.bosco.mine.server.modules.system.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.security.LoginAttemptLimiter;
import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 处理系统用户身份校验、会话创建和当前用户查询。
 */
@Service
public class AuthService {

    private static final ErrorCode INVALID_CREDENTIALS =
            new ErrorCode("AUTH-401-001", "用户名或密码错误");
    private static final ErrorCode INVALID_SESSION =
            new ErrorCode("AUTH-401-002", "登录状态无效");
    private static final ErrorCode ACCOUNT_UNAVAILABLE =
            new ErrorCode("AUTH-403-002", "账号当前不可用");
    private static final ErrorCode CURRENT_PASSWORD_INVALID =
            new ErrorCode("AUTH-422-001", "当前密码错误");
    private static final ErrorCode NEW_PASSWORD_INVALID =
            new ErrorCode("AUTH-422-002", "新密码不符合安全要求");

    private final SystemAuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final String dummyPasswordHash;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建认证服务，并生成一次仅用于抵抗账号枚举时序差异的虚拟散列。
     *
     * @param authMapper      系统认证数据访问接口
     * @param passwordEncoder 密码编码器
     * @param loginAttemptLimiter 登录失败限制器
     */
    public AuthService(
            SystemAuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            LoginAttemptLimiter loginAttemptLimiter
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 校验用户名密码并在 Redis 中创建 Sa-Token 会话。
     *
     * @param username 登录名
     * @param password 明文密码，仅在当前调用栈中使用
     * @param context  登录请求审计上下文
     * @return 登录结果
     */
    public LoginResult login(String username, String password, AuditContext context) {
        String normalizedUsername = username == null ? "" : username.strip();
        if (!loginAttemptLimiter.isAllowed(normalizedUsername, context.clientIp())) {
            recordLoginFailure(null, normalizedUsername, INVALID_CREDENTIALS.code(), context);
            throw new MineException(INVALID_CREDENTIALS, Map.of());
        }
        SystemAuthMapper.AuthUserRecord user = authMapper.findUserByUsername(normalizedUsername).orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);
        if (user == null || !passwordMatches) {
            loginAttemptLimiter.recordFailure(normalizedUsername, context.clientIp());
            recordLoginFailure(user, normalizedUsername, INVALID_CREDENTIALS.code(), context);
            throw new MineException(INVALID_CREDENTIALS, Map.of());
        }
        if (!"ENABLED".equals(user.status())) {
            loginAttemptLimiter.recordFailure(normalizedUsername, context.clientIp());
            recordLoginFailure(user, normalizedUsername, ACCOUNT_UNAVAILABLE.code(), context);
            throw new MineException(ACCOUNT_UNAVAILABLE, Map.of());
        }

        loginAttemptLimiter.recordSuccess(normalizedUsername);
        authMapper.updateLastLoginAt(user.id());
        StpUtil.login(user.id().toString());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        try {
            authMapper.insertLoginLog(
                    idGenerator.next(),
                    user.id(),
                    user.username(),
                    "LOGIN",
                    "SUCCESS",
                    null,
                    context.requestId(),
                    context.traceId(),
                    context.clientIp(),
                    context.userAgent()
            );
        } catch (RuntimeException exception) {
            StpUtil.logout();
            throw exception;
        }
        return new LoginResult(
                tokenInfo.getTokenName(),
                tokenInfo.getTokenValue(),
                toProfile(user)
        );
    }

    /**
     * 获取当前登录用户资料，并再次确认数据库中的用户状态。
     *
     * @return 当前用户资料
     */
    public UserProfile currentUser() {
        UUID userId;
        try {
            userId = UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw new MineException(INVALID_SESSION, Map.of());
        }
        SystemAuthMapper.AuthUserRecord user = authMapper.findUserById(userId)
                .filter(record -> "ENABLED".equals(record.status()))
                .orElseThrow(() -> new MineException(INVALID_SESSION, Map.of()));
        return toProfile(user);
    }

    /**
     * 修改当前用户密码、清除首次改密标记并注销该用户的全部旧会话。
     *
     * @param currentPassword 当前密码
     * @param newPassword     新密码
     * @param context         请求审计上下文
     */
    @Transactional
    public void changePassword(String currentPassword, String newPassword, AuditContext context) {
        SystemAuthMapper.AuthUserRecord user = currentAuthUser();
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new MineException(CURRENT_PASSWORD_INVALID, Map.of());
        }
        if (currentPassword.equals(newPassword)
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new MineException(NEW_PASSWORD_INVALID, Map.of());
        }

        authMapper.updatePassword(user.id(), passwordEncoder.encode(newPassword));
        authMapper.insertPasswordChangeLog(
                idGenerator.next(),
                user.id(),
                user.username(),
                context.requestId(),
                context.traceId(),
                context.clientIp(),
                context.userAgent()
        );
        StpUtil.logout(user.id().toString());
    }

    /**
     * 记录退出审计，并保证即使审计写入失败也注销当前会话。
     *
     * @param context 请求审计上下文
     */
    public void logout(AuditContext context) {
        try {
            SystemAuthMapper.AuthUserRecord user = currentAuthUser();
            authMapper.insertLoginLog(
                    idGenerator.next(),
                    user.id(),
                    user.username(),
                    "LOGOUT",
                    "SUCCESS",
                    null,
                    context.requestId(),
                    context.traceId(),
                    context.clientIp(),
                    context.userAgent()
            );
        } finally {
            StpUtil.logout();
        }
    }

    private UserProfile toProfile(SystemAuthMapper.AuthUserRecord user) {
        return new UserProfile(
                user.id(),
                user.username(),
                user.displayName(),
                user.passwordChangeRequired()
        );
    }

    private void recordLoginFailure(
            SystemAuthMapper.AuthUserRecord user,
            String username,
            String errorCode,
            AuditContext context
    ) {
        authMapper.insertLoginLog(
                idGenerator.next(),
                user == null ? null : user.id(),
                username,
                "LOGIN_FAILURE",
                "FAILURE",
                errorCode,
                context.requestId(),
                context.traceId(),
                context.clientIp(),
                context.userAgent()
        );
    }

    private SystemAuthMapper.AuthUserRecord currentAuthUser() {
        UUID userId;
        try {
            userId = UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw new MineException(INVALID_SESSION, Map.of());
        }
        return authMapper.findUserById(userId)
                .filter(record -> "ENABLED".equals(record.status()))
                .orElseThrow(() -> new MineException(INVALID_SESSION, Map.of()));
    }

    /**
     * 登录成功结果。
     *
     * @param tokenName 令牌请求头名称
     * @param tokenValue 令牌值
     * @param user 用户资料
     */
    public record LoginResult(String tokenName, String tokenValue, UserProfile user) {
    }

    /**
     * 当前用户公开资料。
     *
     * @param id                     用户标识
     * @param username               登录名
     * @param displayName            展示名称
     * @param passwordChangeRequired 是否必须修改初始密码
     */
    public record UserProfile(
            UUID id,
            String username,
            String displayName,
            boolean passwordChangeRequired
    ) {
    }

    /**
     * 与登录、退出和改密审计一起持久化的 HTTP 请求上下文。
     *
     * @param requestId 请求标识
     * @param traceId   链路标识
     * @param clientIp  客户端来源地址
     * @param userAgent 客户端标识
     */
    public record AuditContext(
            String requestId,
            String traceId,
            String clientIp,
            String userAgent
    ) {
    }
}
