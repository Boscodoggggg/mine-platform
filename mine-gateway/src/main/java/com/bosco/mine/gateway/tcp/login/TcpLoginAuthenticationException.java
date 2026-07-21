package com.bosco.mine.gateway.tcp.login;

/**
 * 表示统一 TCP 登录未通过且不暴露具体身份或密钥差异。
 */
public final class TcpLoginAuthenticationException extends RuntimeException {

    /** 创建统一的认证失败。 */
    public TcpLoginAuthenticationException() {
        super("TCP 登录认证失败");
    }
}
