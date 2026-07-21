package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用系统 DNS、TCP Socket 和默认可信 CA 执行接入端点诊断。
 */
@Component
public class SocketDeviceConnectionProbe implements DeviceConnectionProbe {

    private final ExecutorService dnsExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final DeviceProbeTargetPolicy targetPolicy;
    private final Semaphore permits;

    /**
     * 创建设备连接探针。
     *
     * @param targetPolicy 目标地址访问策略
     * @param maxConcurrentProbes 最大并发诊断数
     */
    public SocketDeviceConnectionProbe(
            DeviceProbeTargetPolicy targetPolicy,
            @Value("${mine.device-connection.max-concurrent-probes:8}") int maxConcurrentProbes
    ) {
        this.targetPolicy = targetPolicy;
        this.permits = new Semaphore(Math.max(1, maxConcurrentProbes));
    }

    /**
     * 在一个全局超时预算内执行 DNS、TCP 和可选 TLS 检查。
     *
     * @param profile 接入配置
     * @return 分层诊断结果
     */
    @Override
    public List<DeviceConnectionTestResponse.Stage> probe(DeviceConnectionProfile profile) {
        if (!permits.tryAcquire()) {
            return List.of(failed("DNS", "PROBE_BUSY", 0), skipped("TCP", "DEPENDENCY_SKIPPED"),
                    skipped("TLS", "DEPENDENCY_SKIPPED"));
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(profile.connectTimeoutMillis());
            List<DeviceConnectionTestResponse.Stage> stages = new ArrayList<>();
            InetAddress address = resolve(profile, deadline, stages);
            if (address == null) {
                stages.add(skipped("TCP", "DEPENDENCY_SKIPPED"));
                stages.add(skipped("TLS", "DEPENDENCY_SKIPPED"));
                return List.copyOf(stages);
            }
            connectAndHandshake(profile, address, deadline, stages);
            return List.copyOf(stages);
        } finally {
            permits.release();
        }
    }

    private InetAddress resolve(
            DeviceConnectionProfile profile,
            long deadline,
            List<DeviceConnectionTestResponse.Stage> stages
    ) {
        long started = System.nanoTime();
        Future<InetAddress[]> future = dnsExecutor.submit(() -> InetAddress.getAllByName(profile.host()));
        try {
            InetAddress[] resolved = future.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            List<InetAddress> allowed = targetPolicy.filterAllowed(Arrays.asList(resolved));
            if (allowed.isEmpty()) {
                stages.add(failed("DNS", "DNS_TARGET_BLOCKED", elapsed(started)));
                return null;
            }
            stages.add(passed("DNS", "DNS_OK", elapsed(started)));
            return allowed.getFirst();
        } catch (TimeoutException exception) {
            future.cancel(true);
            stages.add(failed("DNS", "DNS_TIMEOUT", elapsed(started)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stages.add(failed("DNS", "DNS_INTERRUPTED", elapsed(started)));
        } catch (ExecutionException exception) {
            stages.add(failed("DNS", exception.getCause() instanceof UnknownHostException
                    ? "DNS_NOT_FOUND" : "DNS_FAILED", elapsed(started)));
        }
        return null;
    }

    private void connectAndHandshake(
            DeviceConnectionProfile profile,
            InetAddress address,
            long deadline,
            List<DeviceConnectionTestResponse.Stage> stages
    ) {
        long started = System.nanoTime();
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(address, profile.port()), remainingMillis(deadline));
            stages.add(passed("TCP", "TCP_OK", elapsed(started)));
            if (!profile.tlsEnabled()) {
                stages.add(skipped("TLS", "TLS_DISABLED"));
                return;
            }
            handshake(profile, plain, deadline, stages);
        } catch (SocketTimeoutException | TimeoutException exception) {
            stages.add(failed("TCP", "TCP_TIMEOUT", elapsed(started)));
            stages.add(skipped("TLS", "DEPENDENCY_SKIPPED"));
        } catch (ConnectException exception) {
            stages.add(failed("TCP", "TCP_REFUSED", elapsed(started)));
            stages.add(skipped("TLS", "DEPENDENCY_SKIPPED"));
        } catch (IOException | RuntimeException exception) {
            stages.add(failed("TCP", "TCP_FAILED", elapsed(started)));
            stages.add(skipped("TLS", "DEPENDENCY_SKIPPED"));
        }
    }

    private void handshake(
            DeviceConnectionProfile profile,
            Socket plain,
            long deadline,
            List<DeviceConnectionTestResponse.Stage> stages
    ) {
        long started = System.nanoTime();
        String serverName = profile.tlsServerName() == null ? profile.host() : profile.tlsServerName();
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sslSocket = (SSLSocket) factory.createSocket(plain, serverName, profile.port(), true)) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(parameters);
                sslSocket.setSoTimeout(remainingMillis(deadline));
                sslSocket.startHandshake();
                stages.add(passed("TLS", "TLS_OK", elapsed(started)));
            }
        } catch (SocketTimeoutException | TimeoutException exception) {
            stages.add(failed("TLS", "TLS_TIMEOUT", elapsed(started)));
        } catch (IOException | RuntimeException exception) {
            stages.add(failed("TLS", "TLS_FAILED", elapsed(started)));
        }
    }

    private int remainingMillis(long deadline) throws TimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            throw new TimeoutException("设备诊断已超过总超时预算");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private DeviceConnectionTestResponse.Stage passed(String stage, String message, long duration) {
        return new DeviceConnectionTestResponse.Stage(stage, "PASSED", message, duration);
    }

    private DeviceConnectionTestResponse.Stage failed(String stage, String message, long duration) {
        return new DeviceConnectionTestResponse.Stage(stage, "FAILED", message, duration);
    }

    private DeviceConnectionTestResponse.Stage skipped(String stage, String message) {
        return new DeviceConnectionTestResponse.Stage(stage, "SKIPPED", message, 0);
    }

    private long elapsed(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    /**
     * 关闭用于限制 DNS 等待时间的虚拟线程执行器。
     */
    @PreDestroy
    public void close() {
        dnsExecutor.close();
    }
}
