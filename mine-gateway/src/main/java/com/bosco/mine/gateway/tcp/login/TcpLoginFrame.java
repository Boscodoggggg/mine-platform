package com.bosco.mine.gateway.tcp.login;

import java.util.regex.Pattern;

/**
 * 表示统一 TCP 入口在 TLS 内传输的一次登录请求。
 *
 * @param productKey 产品编码
 * @param deviceCode 设备自然编码
 * @param username 平台签发的 TCP 用户名
 * @param secret 平台签发的高熵密钥原文
 */
public record TcpLoginFrame(
        String productKey,
        String deviceCode,
        String username,
        String secret
) {

    private static final Pattern PRODUCT_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern DEVICE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.:@-]{1,128}");

    /** 校验登录字段但不记录或转换密钥原文。 */
    public TcpLoginFrame {
        if (productKey == null || !PRODUCT_KEY.matcher(productKey).matches()) {
            throw new IllegalArgumentException("TCP 登录产品编码格式不合法");
        }
        if (deviceCode == null || !DEVICE_CODE.matcher(deviceCode).matches()) {
            throw new IllegalArgumentException("TCP 登录设备编码格式不合法");
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("TCP 登录用户名格式不合法");
        }
        if (secret == null || secret.length() < 12 || secret.length() > 256) {
            throw new IllegalArgumentException("TCP 登录密钥长度不合法");
        }
    }
}
