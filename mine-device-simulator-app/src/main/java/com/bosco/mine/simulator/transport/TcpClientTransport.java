package com.bosco.mine.simulator.transport;

import com.bosco.mine.simulator.profile.SimulatorProfile;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 主动连接矿山平台 TCP 监听端口的客户端传输实现。
 */
public final class TcpClientTransport implements SimulatorTransport {

    private final SimulatorProfile profile;
    private final Consumer<String> logger;
    private volatile TransportState state = TransportState.DISCONNECTED;
    private Socket socket;
    private OutputStream output;
    private long generation;

    /**
     * 创建 TCP 客户端通道。
     *
     * @param profile 设备配置
     * @param logger 运行日志接收器
     */
    public TcpClientTransport(SimulatorProfile profile, Consumer<String> logger) {
        this.profile = profile;
        this.logger = logger;
    }

    @Override
    public synchronized void connect() throws IOException {
        disconnect();
        state = TransportState.CONNECTING;
        try {
            SocketFactory factory = profile.tlsEnabled()
                    ? SSLSocketFactory.getDefault()
                    : SocketFactory.getDefault();
            socket = factory.createSocket();
            socket.connect(new InetSocketAddress(profile.host(), profile.port()), 10_000);
            socket.setKeepAlive(true);
            output = socket.getOutputStream();
            state = TransportState.CONNECTED;
            logger.accept("TCP 已连接 " + profile.host() + ":" + profile.port());
            long currentGeneration = ++generation;
            Socket connected = socket;
            Thread.ofVirtual().name("simulator-tcp-client-read-" + profile.id())
                    .start(() -> readLoop(connected, currentGeneration));
        } catch (IOException exception) {
            state = TransportState.FAILED;
            closeSocket();
            throw exception;
        }
    }

    @Override
    public synchronized void send(byte[] payload) throws IOException {
        if (state != TransportState.CONNECTED || output == null) {
            throw new IOException("TCP 客户端尚未连接");
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
        closeSocket();
        state = TransportState.DISCONNECTED;
    }

    @Override
    public TransportState state() {
        return state;
    }

    private void closeSocket() {
        output = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响后续重新连接。
            }
            socket = null;
        }
    }

    private void readLoop(Socket connected, long currentGeneration) {
        byte[] buffer = new byte[2048];
        try (InputStream input = connected.getInputStream()) {
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) {
                    logger.accept("TCP 收到 " + length + " 字节：" + PayloadPreview.hex(buffer, length));
                }
            }
            connectionEnded(connected, currentGeneration, "TCP 对端已断开");
        } catch (IOException exception) {
            connectionEnded(connected, currentGeneration, "TCP 读取结束：" + exception.getMessage());
        }
    }

    private synchronized void connectionEnded(Socket connected, long currentGeneration, String message) {
        if (generation != currentGeneration || socket != connected) {
            return;
        }
        closeSocket();
        state = TransportState.DISCONNECTED;
        logger.accept(message);
    }
}
