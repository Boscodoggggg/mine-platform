package com.bosco.mine.examples.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备模拟器命令行入口。
 */
public final class SimulatorApplication {

    private SimulatorApplication() {
    }

    /**
     * 加载场景和设备凭据并执行模拟。密码只能通过文件传入，避免出现在命令历史和进程参数中。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        Scenario scenario = Scenario.load(arguments.scenario());
        char[] password = readPassword(arguments.passwordFile());
        try (PahoMqttTransport transport = new PahoMqttTransport(
                arguments.broker(),
                arguments.username(),
                arguments.username(),
                password
        )) {
            SimulatorRuntime runtime = new SimulatorRuntime(
                    scenario,
                    arguments.username(),
                    transport,
                    SimulatorApplication::sleep,
                    Clock.systemUTC()
            );
            runtime.run();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readPassword(Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) throw new IllegalArgumentException("密码文件不能为空");
            return value.toCharArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取密码文件: " + path, exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟器等待被中断", exception);
        }
    }

    private record Arguments(Path scenario, String broker, String username, Path passwordFile) {

        private static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : args) {
                int delimiter = argument.indexOf('=');
                if (!argument.startsWith("--") || delimiter < 3) {
                    throw usage("参数格式错误: " + argument);
                }
                values.put(argument.substring(2, delimiter), argument.substring(delimiter + 1));
            }
            String scenario = required(values, "scenario");
            String broker = required(values, "broker");
            String username = required(values, "username");
            String passwordFile = required(values, "password-file");
            if (values.size() != 4) throw usage("存在未知或重复参数");
            return new Arguments(Path.of(scenario), broker, username, Path.of(passwordFile));
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) throw usage("缺少参数 --" + key);
            return value;
        }

        private static IllegalArgumentException usage(String reason) {
            return new IllegalArgumentException(reason
                    + System.lineSeparator()
                    + "用法: --scenario=<yaml> --broker=<tcp|ssl URI> --username=<设备用户> "
                    + "--password-file=<密码文件>");
        }
    }
}
