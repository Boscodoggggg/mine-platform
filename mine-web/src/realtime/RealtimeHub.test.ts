import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  RealtimeHub,
  buildRealtimeUrl,
  type RealtimeHubOptions,
  type RealtimeSocket
} from './RealtimeHub';

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

  function createHub(options: Partial<RealtimeHubOptions> = {}) {
    return new RealtimeHub({
      url: 'ws://mine.test/api/v1/realtime',
      token: () => 'token-value',
      createSocket: () => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      },
      random: () => 0,
      ...options
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

  it('矿区位置应复用同一连接并接收快照与批量增量', async () => {
    const listener = vi.fn();
    const hub = createHub();
    const release = hub.subscribeMineLocation('site-a', listener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    expect(JSON.parse(socket.sent[1])).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'MINE_SITE_LOCATION', ids: ['site-a'] }]
    });

    socket.receive({
      type: 'SNAPSHOT',
      topic: 'MINE_SITE_LOCATION',
      siteId: 'site-a',
      watermark: 10,
      source: 'REDIS',
      truncated: false,
      items: [locationItem({ ingestSequence: 10 }), locationItem({ siteId: 'site-b', tagId: 'foreign-tag' })],
      sequence: 2
    });
    await Promise.resolve();
    expect(listener).toHaveBeenLastCalledWith(
      expect.objectContaining({ siteId: 'site-a', mode: 'SNAPSHOT', watermark: 10 })
    );
    expect(listener.mock.calls[0][0].items).toHaveLength(1);

    listener.mockClear();
    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_LOCATION',
      siteId: 'site-a',
      item: locationItem({ sourceSequence: 11, localX: 121 }),
      sequence: 3
    });
    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_LOCATION',
      siteId: 'site-a',
      item: locationItem({ tagId: 'tag-2', tagCode: 'tag-002', sourceSequence: 12 }),
      sequence: 4
    });
    await Promise.resolve();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0][0]).toMatchObject({ siteId: 'site-a', mode: 'UPDATE' });
    expect(listener.mock.calls[0][0].items).toHaveLength(2);

    socket.sent.length = 0;
    release();
    expect(JSON.parse(socket.sent[0])).toMatchObject({
      action: 'UNSUBSCRIBE',
      topics: [{ type: 'MINE_SITE_LOCATION', ids: ['site-a'] }]
    });
  });

  it('空间事件应复用订阅且在服务端序号断档时通知页面重同步', () => {
    const firstListener = vi.fn();
    const secondListener = vi.fn();
    const hub = createHub();
    const releaseFirst = hub.subscribeMineSpatialEvent('site-a', firstListener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    expect(JSON.parse(socket.sent[1])).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'MINE_SITE_SPATIAL_EVENT', ids: ['site-a'] }]
    });
    const subscription = JSON.parse(socket.sent[1]);
    expect(firstListener).not.toHaveBeenCalled();
    socket.receive({ type: 'ACK', action: 'SUBSCRIBE', requestId: subscription.requestId, sequence: 2 });
    expect(firstListener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
    firstListener.mockClear();
    socket.sent.length = 0;
    const releaseSecond = hub.subscribeMineSpatialEvent('site-a', secondListener);
    expect(socket.sent).toHaveLength(0);
    expect(secondListener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
    secondListener.mockClear();

    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_SPATIAL_EVENT',
      siteId: 'site-a',
      item: spatialEvent({ occurredAt: undefined }),
      sequence: 3
    });
    expect(firstListener).not.toHaveBeenCalled();
    expect(secondListener).not.toHaveBeenCalled();

    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_SPATIAL_EVENT',
      siteId: 'site-a',
      item: spatialEvent({ ingestSequence: 11 }),
      sequence: 4
    });
    expect(firstListener).toHaveBeenLastCalledWith(
      expect.objectContaining({ mode: 'UPDATE', siteId: 'site-a' })
    );
    expect(secondListener).toHaveBeenCalledOnce();

    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_SPATIAL_EVENT',
      siteId: 'site-a',
      item: spatialEvent({ eventId: 'event-2', ingestSequence: 12 }),
      sequence: 6
    });
    expect(socket.sent.map(item => JSON.parse(item).action)).toEqual(['RESYNC']);
    expect(firstListener).toHaveBeenCalledWith({ mode: 'RESYNC_REQUIRED', siteId: 'site-a' });

    releaseFirst();
    expect(socket.sent).toHaveLength(1);
    releaseSecond();
    expect(JSON.parse(socket.sent.at(-1)!)).toMatchObject({
      action: 'UNSUBSCRIBE',
      topics: [{ type: 'MINE_SITE_SPATIAL_EVENT', ids: ['site-a'] }]
    });
  });

  it('空间事件连接重建后应主动通知页面补齐离线窗口', () => {
    const listener = vi.fn();
    const hub = createHub();
    hub.subscribeMineSpatialEvent('site-a', listener);
    sockets[0].open();
    const firstAuth = JSON.parse(sockets[0].sent[0]);
    sockets[0].receive({ type: 'ACK', action: 'AUTH', requestId: firstAuth.requestId, sequence: 1 });
    const firstSubscription = JSON.parse(sockets[0].sent[1]);
    sockets[0].receive({
      type: 'ACK',
      action: 'SUBSCRIBE',
      requestId: firstSubscription.requestId,
      sequence: 2
    });
    listener.mockClear();

    sockets[0].remoteClose();
    vi.advanceTimersByTime(1000);
    sockets[1].open();
    const secondAuth = JSON.parse(sockets[1].sent[0]);
    sockets[1].receive({ type: 'ACK', action: 'AUTH', requestId: secondAuth.requestId, sequence: 1 });

    expect(JSON.parse(sockets[1].sent[1])).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'MINE_SITE_SPATIAL_EVENT', ids: ['site-a'] }]
    });
    expect(listener).not.toHaveBeenCalled();
    const secondSubscription = JSON.parse(sockets[1].sent[1]);
    sockets[1].receive({
      type: 'ACK',
      action: 'SUBSCRIBE',
      requestId: secondSubscription.requestId,
      sequence: 2
    });
    expect(listener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
  });

  it('空间订阅失败或超时应显式降级并按上限重试', () => {
    const listener = vi.fn();
    const hub = createHub({
      spatialSubscriptionTimeoutMillis: 1000,
      spatialSubscriptionRetryMillis: 500,
      spatialSubscriptionMaxAttempts: 2
    });
    hub.subscribeMineSpatialEvent('site-a', listener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    const firstSubscription = JSON.parse(socket.sent[1]);

    socket.receive({
      type: 'ERROR',
      requestId: firstSubscription.requestId,
      code: 'REALTIME-LIMIT-EXCEEDED',
      message: 'subscription limit exceeded',
      sequence: 2
    });
    expect(listener).toHaveBeenLastCalledWith({
      mode: 'SUBSCRIPTION_FAILED',
      siteId: 'site-a',
      code: 'REALTIME-LIMIT-EXCEEDED'
    });

    vi.advanceTimersByTime(500);
    const secondSubscription = JSON.parse(socket.sent.at(-1)!);
    expect(secondSubscription).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'MINE_SITE_SPATIAL_EVENT', ids: ['site-a'] }]
    });

    vi.advanceTimersByTime(1000);
    expect(listener).toHaveBeenLastCalledWith({
      mode: 'SUBSCRIPTION_FAILED',
      siteId: 'site-a',
      code: 'REALTIME-SUBSCRIPTION-TIMEOUT'
    });
    vi.advanceTimersByTime(500);
    expect(socket.sent.filter(item => JSON.parse(item).action === 'SUBSCRIBE')).toHaveLength(2);

    const lateListener = vi.fn();
    hub.subscribeMineSpatialEvent('site-a', lateListener);
    expect(lateListener).toHaveBeenCalledWith({
      mode: 'SUBSCRIPTION_FAILED',
      siteId: 'site-a',
      code: 'REALTIME-SUBSCRIPTION-TIMEOUT'
    });
  });

  it('ACK 前的全局序号断档不得提前触发空间事件回查', () => {
    const listener = vi.fn();
    const hub = createHub();
    hub.subscribeMineSpatialEvent('site-a', listener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    const subscription = JSON.parse(socket.sent[1]);

    socket.receive({ type: 'DATA', deviceId: 'device-a', values: { speed: 1 }, sequence: 3 });
    expect(listener).not.toHaveBeenCalledWith({ mode: 'RESYNC_REQUIRED', siteId: 'site-a' });

    socket.receive({
      type: 'ACK',
      action: 'SUBSCRIBE',
      requestId: subscription.requestId,
      sequence: 4
    });
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
  });

  it('运营主题应引用计数并按业务序号去重与发现缺口', () => {
    const firstListener = vi.fn();
    const secondListener = vi.fn();
    const hub = createHub();
    const releaseFirst = hub.subscribeMineOperation('site-a', firstListener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });

    const subscription = JSON.parse(socket.sent[1]);
    expect(subscription).toMatchObject({
      action: 'SUBSCRIBE',
      topics: [{ type: 'MINE_SITE_OPERATION', ids: ['site-a'] }]
    });
    socket.receive({ type: 'ACK', action: 'SUBSCRIBE', requestId: subscription.requestId, sequence: 2 });
    expect(firstListener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
    firstListener.mockClear();

    const releaseSecond = hub.subscribeMineOperation('site-a', secondListener);
    expect(secondListener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
    secondListener.mockClear();
    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_OPERATION',
      siteId: 'site-a',
      businessSequence: 17,
      item: operationEvent(),
      sequence: 3
    });
    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_OPERATION',
      siteId: 'site-a',
      businessSequence: 17,
      item: operationEvent(),
      sequence: 4
    });
    expect(firstListener).toHaveBeenCalledTimes(1);
    expect(secondListener).toHaveBeenCalledTimes(1);
    expect(firstListener).toHaveBeenLastCalledWith(
      expect.objectContaining({ mode: 'UPDATE', siteId: 'site-a' })
    );

    socket.receive({
      type: 'DATA',
      topic: 'MINE_SITE_OPERATION',
      siteId: 'site-a',
      businessSequence: 19,
      item: operationEvent({
        eventId: 'event-19',
        businessSequence: 19,
        occurredAt: '2026-07-20T05:00:19Z'
      }),
      sequence: 5
    });
    expect(firstListener).toHaveBeenCalledWith({
      mode: 'RESYNC_REQUIRED',
      siteId: 'site-a',
      expectedBusinessSequence: 18,
      receivedBusinessSequence: 19
    });

    socket.sent.length = 0;
    releaseFirst();
    expect(socket.sent).toHaveLength(0);
    releaseSecond();
    expect(JSON.parse(socket.sent[0])).toMatchObject({
      action: 'UNSUBSCRIBE',
      topics: [{ type: 'MINE_SITE_OPERATION', ids: ['site-a'] }]
    });
  });

  it('运营连接重建后应请求页面回查离线窗口', () => {
    const listener = vi.fn();
    const hub = createHub();
    hub.subscribeMineOperation('site-a', listener);
    sockets[0].open();
    const firstAuth = JSON.parse(sockets[0].sent[0]);
    sockets[0].receive({ type: 'ACK', action: 'AUTH', requestId: firstAuth.requestId, sequence: 1 });
    const firstSubscription = JSON.parse(sockets[0].sent[1]);
    sockets[0].receive({ type: 'ACK', action: 'SUBSCRIBE', requestId: firstSubscription.requestId, sequence: 2 });
    listener.mockClear();

    sockets[0].remoteClose();
    vi.advanceTimersByTime(1000);
    sockets[1].open();
    const secondAuth = JSON.parse(sockets[1].sent[0]);
    sockets[1].receive({ type: 'ACK', action: 'AUTH', requestId: secondAuth.requestId, sequence: 1 });
    const secondSubscription = JSON.parse(sockets[1].sent[1]);
    sockets[1].receive({ type: 'ACK', action: 'SUBSCRIBE', requestId: secondSubscription.requestId, sequence: 2 });

    expect(listener).toHaveBeenCalledWith({ mode: 'RESYNC_REQUIRED', siteId: 'site-a' });
    expect(listener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
  });

  it('运营订阅失败应独立降级且不影响空间事件订阅', () => {
    const operationListener = vi.fn();
    const spatialListener = vi.fn();
    const hub = createHub();
    hub.subscribeMineSpatialEvent('site-a', spatialListener);
    hub.subscribeMineOperation('site-a', operationListener);
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    const spatialSubscription = JSON.parse(socket.sent[1]);
    const operationSubscription = JSON.parse(socket.sent[2]);

    socket.receive({
      type: 'ERROR',
      requestId: operationSubscription.requestId,
      code: 'REALTIME-LIMIT-EXCEEDED',
      sequence: 2
    });
    socket.receive({
      type: 'ACK',
      action: 'SUBSCRIBE',
      requestId: spatialSubscription.requestId,
      sequence: 3
    });

    expect(operationListener).toHaveBeenCalledWith({
      mode: 'SUBSCRIPTION_FAILED',
      siteId: 'site-a',
      code: 'REALTIME-LIMIT-EXCEEDED'
    });
    expect(spatialListener).toHaveBeenCalledWith({ mode: 'SUBSCRIBED', siteId: 'site-a' });
  });

  it('应向页面发布连接与弱网重连状态', () => {
    const states: string[] = [];
    const hub = createHub();
    hub.subscribeConnectionState(state => states.push(state));
    hub.subscribeMineLocation('site-a', vi.fn());
    const socket = sockets[0];
    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    socket.receive({ type: 'ACK', action: 'AUTH', requestId: auth.requestId, sequence: 1 });
    socket.remoteClose();

    expect(states).toEqual(['DISCONNECTED', 'CONNECTING', 'AUTHENTICATING', 'CONNECTED', 'RECONNECTING']);
  });
});

function locationItem(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'event-1',
    organizationId: 'organization-1',
    siteId: 'site-a',
    sourceDeviceId: 'device-1',
    tagId: 'tag-1',
    tagCode: 'tag-001',
    coordinateSystemId: 'coordinate-1',
    vehicleId: 'vehicle-1',
    personId: null,
    occurredAt: '2026-07-18T00:00:00Z',
    receivedAt: '2026-07-18T00:00:01Z',
    localX: 120,
    localY: 88,
    localZ: null,
    speedMetersPerSecond: 4.5,
    headingDegrees: 90,
    accuracyMeters: 0.5,
    qualityStatus: 'GOOD',
    qualityReason: null,
    ...overrides
  };
}

function spatialEvent(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'event-1',
    organizationId: 'organization-1',
    siteId: 'site-a',
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    tagId: 'tag-1',
    vehicleId: 'vehicle-1',
    personId: null,
    eventType: 'ENTER',
    anchorLocationEventId: 'location-event-1',
    previousState: 'OUTSIDE',
    currentState: 'INSIDE',
    confirmationPoints: 3,
    ingestSequence: 11,
    runtimeRevision: 5,
    occurredAt: '2026-07-18T00:00:00Z',
    evidence: {
      localX: 120,
      localY: 88,
      localZ: null,
      classification: 'INSIDE',
      qualityStatus: 'GOOD',
      qualityReason: null,
      zoneContentHash: `sha256:${'a'.repeat(64)}`
    },
    ...overrides
  };
}

function operationEvent(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'event-17',
    organizationId: 'organization-1',
    siteId: 'site-a',
    aggregateType: 'OPERATION_RECORD',
    aggregateId: 'record-1',
    updateType: 'RECORD_COMPLETED',
    businessSequence: 17,
    occurredAt: '2026-07-20T05:00:17Z',
    summary: { operationRecordId: 'record-1', effective: true },
    ...overrides
  };
}
