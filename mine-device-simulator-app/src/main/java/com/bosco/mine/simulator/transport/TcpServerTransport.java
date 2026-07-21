package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.SimulatorProfile;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 在本机监听端口并等待矿山平台主动连接的 TCP 服务端传输实现。
 */
public final class TcpServerTransport implements SimulatorTransport {

    private static final int MAX_BIND_ATTEMPTS = 20;
    private static final long BIND_RETRY_DELAY_MILLIS = 25;

    private final SimulatorProfile profile;
    private final Consumer<String> logger;
    private volatile TransportState state = TransportState.DISCONNECTED;
    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile OutputStream output;
    private long generation;

    /**
     * 创建 TCP 服务端通道。
     *
     * @param profile 设备配置
     * @param logger 运行日志接收器
     */
    public TcpServerTransport(SimulatorProfile profile, Consumer<String> logger) {
        this.profile = profile;
        this.logger = logger;
    }

    @Override
    public synchronized void connect() throws IOException {
        disconnect();
        ServerSocketFactory factory = profile.tlsEnabled()
                ? SSLServerSocketFactory.getDefault()
                : ServerSocketFactory.getDefault();
        try {
            ServerSocket listener = bindListener(factory);
            serverSocket = listener;
            state = TransportState.WAITING;
            logger.accept("TCP 正在监听 " + profile.host() + ":" + profile.port());
            long currentGeneration = ++generation;
            startAccept(listener, currentGeneration);
        } catch (IOException exception) {
            state = TransportState.FAILED;
            throw exception;
        }
    }

    /**
     * 创建并绑定监听套接字。端口在旧监听关闭后可能短暂不可复用，因此仅对地址占用执行有限重试。
     *
     * @param factory 服务端套接字工厂
     * @return 已绑定的监听套接字
     * @throws IOException 创建、绑定失败或重试被中断
     */
    private ServerSocket bindListener(ServerSocketFactory factory) throws IOException {
        BindException lastBindException = null;
        for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS; attempt++) {
            ServerSocket listener = factory.createServerSocket();
            try {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(profile.host(), profile.port()));
                return listener;
            } catch (BindException exception) {
                lastBindException = exception;
                close(listener);
                if (attempt < MAX_BIND_ATTEMPTS) {
                    awaitBindRetry(exception);
                }
            } catch (IOException | RuntimeException exception) {
                close(listener);
                throw exception;
            }
        }
        throw lastBindException;
    }

    /**
     * 等待下一次端口绑定尝试，并保留线程中断语义。
     *
     * @param bindException 最近一次绑定异常
     * @throws IOException 当前线程被中断
     */
    private static void awaitBindRetry(BindException bindException) throws IOException {
        try {
            Thread.sleep(BIND_RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException("TCP 监听端口重试被中断", exception);
            interrupted.addSuppressed(bindException);
            throw interrupted;
        }
    }

    private void startAccept(ServerSocket listener, long currentGeneration) {
        Thread.ofVirtual().name("simulator-tcp-accept-" + profile.id())
                .start(() -> accept(listener, currentGeneration));
    }

    private void accept(ServerSocket listener, long currentGeneration) {
        try {
            Socket accepted = listener.accept();
            accepted.setKeepAlive(true);
            synchronized (this) {
                if (generation != currentGeneration || serverSocket != listener || listener.isClosed()) {
                    accepted.close();
                    return;
                }
                clientSocket = accepted;
                output = accepted.getOutputStream();
                state = TransportState.CONNECTED;
            }
            logger.accept("平台已接入 " + accepted.getRemoteSocketAddress());
            Thread.ofVirtual().name("simulator-tcp-server-read-" + profile.id())
                    .start(() -> readLoop(listener, accepted, currentGeneration));
        } catch (IOException exception) {
            synchronized (this) {
                if (generation != currentGeneration || serverSocket != listener || listener.isClosed()) {
                    return;
                }
                state = TransportState.FAILED;
                logger.accept("TCP 等待连接失败：" + exception.getMessage());
            }
        }
    }

    @Override
    public synchronized void send(byte[] payload) throws IOException {
        if (state != TransportState.CONNECTED || output == null) {
            throw new IOException("TCP 服务端尚未收到平台连接");
        }
        try {
            output.write(payload);
            output.flush();
        } catch (IOException exception) {
            state = TransportState.FAILED;
            throw exception;
        }
    }

    @Override
    public synchronized void disconnect() {
        generation++;
        output = null;
        close(clientSocket);
        clientSocket = null;
        close(serverSocket);
        serverSocket = null;
        state = TransportState.DISCONNECTED;
    }

    @Override
    public TransportState state() {
        return state;
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响资源状态收敛。
        }
    }

    private void readLoop(ServerSocket listener, Socket connected, long currentGeneration) {
        byte[] buffer = new byte[2048];
        try (InputStream input = connected.getInputStream()) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) {
                    logger.accept("TCP 收到 " + length + " 字节：" + PayloadPreview.hex(buffer, length));
                }
            }
            clientEnded(listener, connected, currentGeneration, "平台连接已断开");
        } catch (IOException exception) {
            clientEnded(listener, connected, currentGeneration, "平台连接读取结束：" + exception.getMessage());
        }
    }

    private synchronized void clientEnded(ServerSocket listener, Socket connected, long currentGeneration,
            String message) {
        if (generation != currentGeneration || serverSocket != listener || clientSocket != connected) {
            return;
        }
        output = null;
        close(clientSocket);
        clientSocket = null;
        logger.accept(message);
        if (!listener.isClosed()) {
            state = TransportState.WAITING;
            startAccept(listener, currentGeneration);
        } else {
            state = TransportState.DISCONNECTED;
        }
    }
}
