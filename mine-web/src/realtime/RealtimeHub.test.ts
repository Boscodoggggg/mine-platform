import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RealtimeHub, buildRealtimeUrl, type RealtimeSocket } from './RealtimeHub';

class FakeSocket implements RealtimeSocket {
  readyState: number = WebSocket.CONNECTING;
  readonly sent: string[] = [];
  private readonly listeners = new Map<string, EventListenerOrEventListenerObject[]>();

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const entries = this.listeners.get(type) ?? [];
    entries.push(listener);
    this.listeners.set(type, entries);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    if (this.readyState === WebSocket.CLOSED) return;
    this.readyState = WebSocket.CLOSED;
    this.emit('close', new CloseEvent('close'));
  }

  open() {
    this.readyState = WebSocket.OPEN;
    this.emit('open', new Event('open'));
  }

  receive(frame: Record<string, unknown>) {
    this.emit('message', new MessageEvent('message', { data: JSON.stringify(frame) }));
  }

  remoteClose() {
    this.readyState = WebSocket.CLOSED;
    this.emit('close', new CloseEvent('close'));
  }

  private emit(type: string, event: Event) {
    this.listeners.get(type)?.forEach(listener => {
      if (typeof listener === 'function') listener(event);
      else listener.handleEvent(event);
    });
  }
}

describe('RealtimeHub', () => {
  const sockets: FakeSocket[] = [];

  beforeEach(() => {
    vi.useFakeTimers();
    sockets.length = 0;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function createHub() {
    return new RealtimeHub({
      url: 'ws://mine.test/api/v1/realtime',
      token: () => 'token-value',
      createSocket: () => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      },
      random: () => 0
    });
  }

  it('应根据管理接口地址生成同源或指定主机的WebSocket地址', () => {
    expect(buildRealtimeUrl('/api/v1', 'https://mine.example')).toBe('wss://mine.example/api/v1/realtime');
    expect(buildRealtimeUrl('http://10.0.0.8:8080/api/v1', 'https://mine.example')).toBe(
      'ws://10.0.0.8:8080/api/v1/realtime'
    );
  });

  it('连接后应先鉴权并在鉴权成功后恢复现有订阅', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());

    sockets[0].open();
    const auth = JSON.parse(sockets[0].sent[0]);
    expect(auth).toMatchObject({ action: 'AUTH', token: 'token-value' });
    expect(sockets[0].sent).toHaveLength(1);

    sockets[0].receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    expect(JSON.parse(sockets[0].sent[1])).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'DEVICE_LATEST', ids: ['device-a'], properties: ['speed'] }]
    });
  });

  it('重叠订阅应引用计数且只发送必要的订阅变化', () => {
    const hub = createHub();
    const releaseFirst = hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    socket.sent.length = 0;

    const releaseSecond = hub.subscribe('device-a', ['speed'], vi.fn());
    releaseFirst();
    expect(socket.sent).toHaveLength(0);

    releaseSecond();
    expect(JSON.parse(socket.sent[0])).toMatchObject({
      action: 'UNSUBSCRIBE',
      topics: [{ type: 'DEVICE_LATEST', ids: ['device-a'], properties: ['speed'] }]
    });
  });

  it('发现服务端序号断档时应只触发一次显式快照同步', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    socket.sent.length = 0;

    socket.receive({ type: 'DATA', deviceId: 'device-a', values: { speed: 1 }, sequence: 3 });
    socket.receive({ type: 'DATA', deviceId: 'device-a', values: { speed: 2 }, sequence: 5 });

    expect(socket.sent.map(item => JSON.parse(item).action)).toEqual(['RESYNC']);
  });

  it('弱网断开后应按指数退避重连且不使用固定轮询', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());
    sockets[0].open();
    sockets[0].remoteClose();

    vi.advanceTimersByTime(999);
    expect(sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(sockets).toHaveLength(2);

    sockets[1].remoteClose();
    vi.advanceTimersByTime(1999);
    expect(sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(sockets).toHaveLength(3);
  });

  it('鉴权后应发送应用层心跳但不触发任何数据查询', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    socket.sent.length = 0;

    vi.advanceTimersByTime(24_999);
    expect(socket.sent).toHaveLength(0);
    vi.advanceTimersByTime(1);

    expect(JSON.parse(socket.sent[0])).toMatchObject({ action: 'PING' });
  });

  it('心跳超时应关闭半开连接并进入退避重连', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    vi.advanceTimersByTime(25_000);
    expect(JSON.parse(socket.sent.at(-1)!)).toMatchObject({ action: 'PING' });
    vi.advanceTimersByTime(10_000);

    expect(socket.readyState).toBe(WebSocket.CLOSED);
    vi.advanceTimersByTime(1000);
    expect(sockets).toHaveLength(2);
  });

  it('收到心跳响应后应取消超时并安排下一次心跳', () => {
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    vi.advanceTimersByTime(25_000);
    socket.receive({ type: 'PONG', sequence: 2 });
    vi.advanceTimersByTime(24_999);
    expect(socket.sent.filter(item => JSON.parse(item).action === 'PING')).toHaveLength(1);
    vi.advanceTimersByTime(1);

    expect(socket.sent.filter(item => JSON.parse(item).action === 'PING')).toHaveLength(2);
    expect(socket.readyState).toBe(WebSocket.OPEN);
  });

  it('服务端判定令牌过期后应停止重连并通知认证层', () => {
    const onAuthenticationFailed = vi.fn();
    const hub = new RealtimeHub({
      url: 'ws://mine.test/api/v1/realtime',
      token: () => 'expired-token',
      createSocket: () => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      },
      onAuthenticationFailed
    });
    hub.subscribe('device-a', ['speed'], vi.fn());
    const socket = sockets[0];
    socket.open();

    socket.receive({
      type: 'ERROR',
      code: 'REALTIME-AUTH-FAILED',
      message: '令牌无效或已过期',
      sequence: 1
    });
    vi.advanceTimersByTime(60_000);

    expect(onAuthenticationFailed).toHaveBeenCalledWith('令牌无效或已过期');
    expect(sockets).toHaveLength(1);
  });

  it('同一事件循环的多条数据应合并通知并更新最新值缓存', async () => {
    const listener = vi.fn();
    const hub = createHub();
    hub.subscribe('device-a', ['speed'], listener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    socket.receive({ type: 'DATA', deviceId: 'device-a', values: { speed: 10 }, sequence: 2 });
    socket.receive({ type: 'DATA', deviceId: 'device-a', values: { speed: 11 }, sequence: 3 });
    expect(listener).not.toHaveBeenCalled();

    await Promise.resolve();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(hub.getLatest('device-a', 'speed')?.value).toBe(11);
  });
});
