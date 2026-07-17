package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.SimulatorProfile;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 在本机监听端口并等待矿山平台主动连接的 TCP 服务端传输实现。
 */
public final class TcpServerTransport implements SimulatorTransport {

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
        serverSocket = factory.createServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(profile.host(), profile.port()));
        state = TransportState.WAITING;
        logger.accept("TCP 正在监听 " + profile.host() + ":" + profile.port());
        long currentGeneration = ++generation;
        startAccept(serverSocket, currentGeneration);
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
