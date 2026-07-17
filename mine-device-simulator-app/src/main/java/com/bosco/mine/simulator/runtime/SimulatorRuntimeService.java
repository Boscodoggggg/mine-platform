package com.bosco.mine.simulator.runtime;

import com.bosco.mine.simulator.payload.PayloadRenderer;
import com.bosco.mine.simulator.profile.ProfileStore;
import com.bosco.mine.simulator.profile.SimulatorProfile;
import com.bosco.mine.simulator.transport.SimulatorTransport;
import com.bosco.mine.simulator.transport.TransportFactory;
import com.bosco.mine.simulator.transport.TransportState;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理全部模拟设备的连接、周期发送和实时运行状态。
 */
public final class SimulatorRuntimeService implements AutoCloseable {

    private static final int MAX_LOGS = 200;

    private final ProfileStore profileStore;
    private final PayloadRenderer payloadRenderer;
    private final TransportFactory transportFactory;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            Thread.ofVirtual().name("simulator-sender-", 0).factory());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 创建模拟任务运行服务。
     *
     * @param profileStore 配置仓库
     * @param payloadRenderer 报文渲染器
     * @param transportFactory 传输工厂
     */
    public SimulatorRuntimeService(ProfileStore profileStore, PayloadRenderer payloadRenderer,
            TransportFactory transportFactory) {
        this.profileStore = profileStore;
        this.payloadRenderer = payloadRenderer;
        this.transportFactory = transportFactory;
    }

    /**
     * 启动指定设备并开启周期发送。
     *
     * @param profileId 配置标识
     * @param password 本次运行临时密码
     * @return 启动后的状态
     */
    public synchronized RuntimeSnapshot start(String profileId, char[] password) {
        SimulatorProfile profile = requireProfile(profileId);
        Session previous = sessions.remove(profileId);
        if (previous != null) {
            previous.stop();
        }
        Session session = new Session(profile);
        sessions.put(profileId, session);
        try {
            session.transport = transportFactory.create(profile, password, session::info);
            session.transport.connect();
            session.running = true;
            session.info("模拟任务已启动");
            session.future = scheduler.scheduleWithFixedDelay(
                    () -> sendScheduled(session), 0, profile.intervalMillis(), TimeUnit.MILLISECONDS);
            return session.snapshot();
        } catch (IOException | RuntimeException exception) {
            session.error("启动失败：" + exception.getMessage());
            if (session.transport != null) {
                session.transport.close();
            }
            throw new IllegalStateException("无法启动设备“" + profile.name() + "”：" + exception.getMessage(), exception);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /**
     * 停止周期任务并释放连接。
     *
     * @param profileId 配置标识
     * @return 停止后的状态
     */
    public synchronized RuntimeSnapshot stop(String profileId) {
        Session session = requireSession(profileId);
        session.stop();
        session.info("模拟任务已停止");
        return session.snapshot();
    }

    /**
     * 立即发送一帧报文。
     *
     * @param profileId 配置标识
     * @return 发送后的状态
     */
    public RuntimeSnapshot sendOnce(String profileId) {
        Session session = requireSession(profileId);
        send(session, true);
        return session.snapshot();
    }

    /**
     * 临时断开传输连接，保留任务统计信息。
     *
     * @param profileId 配置标识
     * @return 断开后的状态
     */
    public RuntimeSnapshot disconnect(String profileId) {
        Session session = requireSession(profileId);
        session.transport.disconnect();
        session.info("连接已手动断开");
        return session.snapshot();
    }

    /**
     * 使用当前配置重新建立连接。
     *
     * @param profileId 配置标识
     * @return 重连后的状态
     */
    public RuntimeSnapshot reconnect(String profileId) {
        Session session = requireSession(profileId);
        try {
            session.transport.connect();
            session.info("连接已重新建立");
            return session.snapshot();
        } catch (IOException exception) {
            session.error("重连失败：" + exception.getMessage());
            throw new IllegalStateException("重新连接失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 查询指定设备运行状态；尚未启动时返回初始状态。
     *
     * @param profileId 配置标识
     * @return 运行状态
     */
    public RuntimeSnapshot find(String profileId) {
        Session session = sessions.get(profileId);
        if (session != null) {
            return session.snapshot();
        }
        requireProfile(profileId);
        return new RuntimeSnapshot(profileId, false, TransportState.DISCONNECTED,
                0, 0, null, "", List.of());
    }

    /**
     * 查询全部已配置设备的运行状态。
     *
     * @return 状态列表
     */
    public List<RuntimeSnapshot> findAll() {
        return profileStore.findAll().stream()
                .map(profile -> find(profile.id()))
                .sorted(Comparator.comparing(RuntimeSnapshot::profileId))
                .toList();
    }

    /**
     * 删除配置前清理对应任务。
     *
     * @param profileId 配置标识
     */
    public synchronized void remove(String profileId) {
        Session session = sessions.remove(profileId);
        if (session != null) {
            session.stop();
        }
    }

    private void sendScheduled(Session session) {
        if (!session.running || session.transport.state() != TransportState.CONNECTED) {
            return;
        }
        send(session, false);
    }

    private void send(Session session, boolean manual) {
        if (session.transport == null || session.transport.state() != TransportState.CONNECTED) {
            throw new IllegalStateException("设备连接尚未就绪，当前状态："
                    + (session.transport == null ? TransportState.DISCONNECTED : session.transport.state()));
        }
        long sequence = session.sequence.incrementAndGet();
        try {
            byte[] payload = payloadRenderer.render(session.profile, sequence, Instant.now());
            session.transport.send(payload);
            session.sentCount.incrementAndGet();
            session.lastSentAt = Instant.now();
            session.info((manual ? "手动" : "周期") + "发送成功，序号 " + sequence + "，"
                    + payload.length + " 字节");
        } catch (IOException | RuntimeException exception) {
            session.failedCount.incrementAndGet();
            session.error("发送失败：" + exception.getMessage());
            if (manual) {
                throw new IllegalStateException("发送失败：" + exception.getMessage(), exception);
            }
        }
    }

    private SimulatorProfile requireProfile(String profileId) {
        return profileStore.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("设备配置不存在：" + profileId));
    }

    private Session requireSession(String profileId) {
        Session session = sessions.get(profileId);
        if (session == null || session.transport == null) {
            throw new IllegalStateException("设备尚未启动：" + profileId);
        }
        return session;
    }

    /**
     * 关闭全部模拟任务。
     */
    @Override
    @PreDestroy
    public synchronized void close() {
        sessions.values().forEach(Session::stop);
        scheduler.shutdownNow();
    }

    private static final class Session {
        private final SimulatorProfile profile;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong sentCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();
        private final ArrayDeque<RuntimeLog> logs = new ArrayDeque<>();
        private volatile SimulatorTransport transport;
        private volatile ScheduledFuture<?> future;
        private volatile boolean running;
        private volatile Instant lastSentAt;
        private volatile String lastError = "";

        private Session(SimulatorProfile profile) {
            this.profile = profile;
        }

        private void info(String message) {
            addLog("INFO", message);
        }

        private void error(String message) {
            lastError = message;
            addLog("ERROR", message);
        }

        private synchronized void addLog(String level, String message) {
            logs.addFirst(new RuntimeLog(Instant.now(), level, message));
            while (logs.size() > MAX_LOGS) {
                logs.removeLast();
            }
        }

        private synchronized RuntimeSnapshot snapshot() {
            TransportState state = transport == null ? TransportState.DISCONNECTED : transport.state();
            return new RuntimeSnapshot(profile.id(), running, state, sentCount.get(), failedCount.get(),
                    lastSentAt, lastError, List.copyOf(new ArrayList<>(logs)));
        }

        private synchronized void stop() {
            running = false;
            if (future != null) {
                future.cancel(false);
                future = null;
            }
            if (transport != null) {
                transport.close();
            }
        }
    }
}
