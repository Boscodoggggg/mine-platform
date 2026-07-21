package com.bosco.mine.protocol.engine.snapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对协议快照 JSON 进行对象字段排序后计算稳定 SHA-256。
 */
public final class ProtocolSnapshotHasher {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * 计算不受 JSON 对象字段顺序影响的内容哈希。
     *
     * @param json 快照 JSON
     * @return 带算法前缀的小写十六进制哈希
     */
    public String sha256(String json) {
        try {
            Object value = jsonMapper.readValue(json, Object.class);
            byte[] canonical = jsonMapper.writeValueAsString(canonicalize(value))
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("协议快照不是合法 JSON", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> canonical = new ArrayList<>(list.size());
            for (Object item : list) canonical.add(canonicalize(item));
            return canonical;
        }
        return value;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
