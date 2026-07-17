package com.bosco.mine.simulator.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 使用 YAML 文件持久化模拟设备配置的存储实现。
 * <p>
 * 密码和密钥不属于配置模型，因此不会被写入磁盘。写入时先生成同目录临时文件，
 * 再原子替换目标文件，降低异常退出导致配置损坏的概率。
 * </p>
 */
public final class YamlProfileStore implements ProfileStore {

    private static final int FORMAT_VERSION = 1;
    private static final Collator CHINESE_COLLATOR = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

    private final Path file;
    private final Map<String, SimulatorProfile> profiles = new LinkedHashMap<>();

    /**
     * 创建 YAML 配置仓库并读取已有文件。
     *
     * @param file 配置文件路径
     */
    public YamlProfileStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    @Override
    public synchronized List<SimulatorProfile> findAll() {
        return profiles.values().stream()
                .sorted(Comparator.comparing(SimulatorProfile::name, CHINESE_COLLATOR)
                        .thenComparing(SimulatorProfile::id))
                .toList();
    }

    @Override
    public synchronized Optional<SimulatorProfile> findById(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    @Override
    public synchronized SimulatorProfile save(SimulatorProfile profile) {
        SimulatorProfile previous = profiles.put(profile.id(), profile);
        try {
            persist();
        } catch (RuntimeException exception) {
            if (previous == null) {
                profiles.remove(profile.id());
            } else {
                profiles.put(profile.id(), previous);
            }
            throw exception;
        }
        return profile;
    }

    @Override
    public synchronized boolean delete(String id) {
        SimulatorProfile removed = profiles.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException exception) {
            profiles.put(id, removed);
            throw exception;
        }
        return true;
    }

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (InputStream input = Files.newInputStream(file)) {
            Object loaded = yaml.load(input);
            if (loaded == null) {
                return;
            }
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("YAML 根节点必须是对象");
            }
            Object entries = root.get("profiles");
            if (entries == null) {
                return;
            }
            if (!(entries instanceof List<?> list)) {
                throw new IllegalArgumentException("profiles 必须是数组");
            }
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("配置项必须是对象");
                }
                SimulatorProfile profile = fromMap(values);
                profiles.put(profile.id(), profile);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法读取模拟器配置文件：" + file, exception);
        }
    }

    private void persist() {
        Path parent = file.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            Files.writeString(temporary, createYaml().dump(toDocument()), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存模拟器配置文件：" + file, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理失败不应覆盖原始保存异常。
                }
            }
        }
    }

    private Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", FORMAT_VERSION);
        List<Map<String, Object>> entries = new ArrayList<>();
        findAll().forEach(profile -> entries.add(toMap(profile)));
        document.put("profiles", entries);
        return document;
    }

    private static Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options);
    }

    private static Map<String, Object> toMap(SimulatorProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", profile.id());
        values.put("name", profile.name());
        values.put("transportMode", profile.transportMode().name());
        values.put("host", profile.host());
        values.put("port", profile.port());
        values.put("tlsEnabled", profile.tlsEnabled());
        values.put("productKey", profile.productKey());
        values.put("deviceCode", profile.deviceCode());
        values.put("clientId", profile.clientId());
        values.put("username", profile.username());
        values.put("publishTopic", profile.publishTopic());
        values.put("payloadFormat", profile.payloadFormat().name());
        values.put("payloadTemplate", profile.payloadTemplate());
        values.put("intervalMillis", profile.intervalMillis());
        values.put("qos", profile.qos());
        return values;
    }

    private static SimulatorProfile fromMap(Map<?, ?> values) {
        return new SimulatorProfile(
                text(values, "id"),
                text(values, "name"),
                TransportMode.valueOf(text(values, "transportMode")),
                text(values, "host"),
                integer(values, "port"),
                bool(values, "tlsEnabled"),
                text(values, "productKey"),
                text(values, "deviceCode"),
                text(values, "clientId"),
                text(values, "username"),
                text(values, "publishTopic"),
                PayloadFormat.valueOf(text(values, "payloadFormat")),
                text(values, "payloadTemplate"),
                longValue(values, "intervalMillis"),
                integer(values, "qos"));
    }

    private static String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Map<?, ?> values, String key) {
        return Math.toIntExact(longValue(values, key));
    }

    private static long longValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(text(values, key));
    }

    private static boolean bool(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(text(values, key));
    }
}
