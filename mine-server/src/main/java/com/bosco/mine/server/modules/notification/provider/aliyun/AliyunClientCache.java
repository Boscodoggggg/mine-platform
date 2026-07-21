package com.bosco.mine.server.modules.notification.provider.aliyun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以凭据指纹为键有界复用阿里云官方 SDK 客户端。
 *
 * @param <T> SDK 客户端类型
 */
final class AliyunClientCache<T> {

    private static final int MAX_CLIENTS = 64;
    private final Map<String, T> clients = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
            return size() > MAX_CLIENTS;
        }
    };

    /** 返回已有客户端或原子创建一个新客户端。 */
    synchronized T get(String fingerprint, ClientFactory<T> factory) throws Exception {
        T existing = clients.get(fingerprint);
        if (existing != null) {
            return existing;
        }
        T created = factory.create();
        clients.put(fingerprint, created);
        return created;
    }

    /** 计算不泄露原始凭据的稳定 SHA-256 指纹。 */
    static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持 SHA-256", exception);
        }
    }

    /** 支持受检异常的 SDK 客户端工厂。 */
    @FunctionalInterface
    interface ClientFactory<T> {
        /** 创建 SDK 客户端。 */
        T create() throws Exception;
    }
}
