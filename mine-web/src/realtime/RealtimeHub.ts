export interface RealtimeSocket {
  readonly readyState: number;
  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface RealtimeLatestValue {
  deviceId: string;
  propertyId: string;
  value: number;
  occurredAt?: string;
  receivedAt?: string;
  quality?: string;
  eventId?: string;
}

export interface RealtimeBatch {
  values: readonly RealtimeLatestValue[];
}

export type RealtimeListener = (batch: RealtimeBatch) => void;

export interface RealtimeHubOptions {
  url: string;
  token: () => string;
  createSocket?: (url: string) => RealtimeSocket;
  random?: () => number;
  reconnectBaseMillis?: number;
  reconnectMaxMillis?: number;
  heartbeatMillis?: number;
  heartbeatTimeoutMillis?: number;
  onAuthenticationFailed?: (message: string) => void;
}

/** 根据管理 API 根地址生成统一实时端点地址。 */
export function buildRealtimeUrl(apiBaseUrl: string, browserOrigin: string) {
  const url = new URL(apiBaseUrl, browserOrigin);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.pathname = `${url.pathname.replace(/\/$/, '')}/realtime`;
  url.search = '';
  url.hash = '';
  return url.toString().replace(/\/$/, '');
}

interface Registration {
  deviceId: string;
  properties: ReadonlySet<string>;
  listener: RealtimeListener;
}

interface ServerFrame {
  type?: string;
  action?: string;
  requestId?: string;
  sequence?: number;
  deviceId?: string;
  eventId?: string;
  occurredAt?: string;
  receivedAt?: string;
  quality?: string;
  code?: string;
  message?: string;
  values?: Record<string, number>;
  items?: Array<{
    propertyId?: string;
    value?: number;
    occurredAt?: string;
    receivedAt?: string;
    quality?: string;
    eventId?: string;
  }>;
}

/**
 * 管理全站唯一实时连接、弱网重连、订阅引用计数和最新值缓存。
 * 正常运行仅消费服务端推送；序号断档时才显式请求快照，不做固定轮询。
 */
export class RealtimeHub {
  private readonly options: Required<
    Pick<
      RealtimeHubOptions,
      | 'createSocket'
      | 'random'
      | 'reconnectBaseMillis'
      | 'reconnectMaxMillis'
      | 'heartbeatMillis'
      | 'heartbeatTimeoutMillis'
      | 'onAuthenticationFailed'
    >
  > &
    Omit<
      RealtimeHubOptions,
      | 'createSocket'
      | 'random'
      | 'reconnectBaseMillis'
      | 'reconnectMaxMillis'
      | 'heartbeatMillis'
      | 'heartbeatTimeoutMillis'
      | 'onAuthenticationFailed'
    >;

  private readonly registrations = new Set<Registration>();
  private readonly propertyReferences = new Map<string, Map<string, number>>();
  private readonly latestValues = new Map<string, RealtimeLatestValue>();
  private readonly pendingRegistrations = new Set<Registration>();
  private socket: RealtimeSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;
  private requestSequence = 0;
  private lastServerSequence = 0;
  private authenticated = false;
  private desired = false;
  private resyncPending = false;
  private flushPending = false;

  constructor(options: RealtimeHubOptions) {
    this.options = {
      ...options,
      createSocket: options.createSocket ?? (url => new WebSocket(url)),
      random: options.random ?? Math.random,
      reconnectBaseMillis: options.reconnectBaseMillis ?? 1000,
      reconnectMaxMillis: options.reconnectMaxMillis ?? 30_000,
      heartbeatMillis: options.heartbeatMillis ?? 25_000,
      heartbeatTimeoutMillis: options.heartbeatTimeoutMillis ?? 10_000,
      onAuthenticationFailed: options.onAuthenticationFailed ?? (() => undefined)
    };
  }

  /** 建立实时连接；重复调用不会创建第二条连接。 */
  connect() {
    this.desired = true;
    if (
      this.socket &&
      (this.socket.readyState === WebSocket.CONNECTING || this.socket.readyState === WebSocket.OPEN)
    )
      return;
    this.openSocket();
  }

  /** 主动关闭实时连接并取消待执行的重连。 */
  disconnect() {
    this.desired = false;
    this.authenticated = false;
    this.clearReconnectTimer();
    this.clearHeartbeatTimer();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'client disconnect');
  }

  /**
   * 订阅设备属性最新值，返回释放函数。多个页面订阅同一属性只会向服务端订阅一次。
   */
  subscribe(deviceId: string, properties: readonly string[], listener: RealtimeListener): () => void {
    const normalized = new Set(properties.map(item => item.trim()).filter(Boolean));
    if (!deviceId.trim() || normalized.size === 0) throw new Error('设备和属性不能为空');
    const registration: Registration = { deviceId, properties: normalized, listener };
    this.registrations.add(registration);

    const activated: string[] = [];
    const references = this.propertyReferences.get(deviceId) ?? new Map<string, number>();
    normalized.forEach(propertyId => {
      const count = references.get(propertyId) ?? 0;
      references.set(propertyId, count + 1);
      if (count === 0) activated.push(propertyId);
    });
    this.propertyReferences.set(deviceId, references);
    this.connect();
    if (activated.length > 0 && this.authenticated) this.sendTopic('SUBSCRIBE', deviceId, activated);

    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.registrations.delete(registration);
      const removed: string[] = [];
      normalized.forEach(propertyId => {
        const count = references.get(propertyId) ?? 0;
        if (count <= 1) {
          references.delete(propertyId);
          removed.push(propertyId);
        } else {
          references.set(propertyId, count - 1);
        }
      });
      if (references.size === 0) this.propertyReferences.delete(deviceId);
      if (removed.length > 0 && this.authenticated) this.sendTopic('UNSUBSCRIBE', deviceId, removed);
    };
  }

  /** 返回内存中的单个属性最新值。 */
  getLatest(deviceId: string, propertyId: string) {
    return this.latestValues.get(this.latestKey(deviceId, propertyId));
  }

  private openSocket() {
    this.clearReconnectTimer();
    const socket = this.options.createSocket(this.options.url);
    this.socket = socket;
    this.authenticated = false;
    this.clearHeartbeatTimer();
    this.lastServerSequence = 0;
    this.resyncPending = false;
    socket.addEventListener('open', () => {
      if (this.socket !== socket) return;
      this.reconnectAttempt = 0;
      this.sendRaw({ action: 'AUTH', requestId: this.nextRequestId(), token: this.options.token() });
    });
    socket.addEventListener('message', event => {
      if (this.socket !== socket) return;
      this.handleMessage((event as MessageEvent<string>).data);
    });
    socket.addEventListener('close', () => {
      if (this.socket === socket) this.socket = null;
      this.authenticated = false;
      this.clearHeartbeatTimer();
      if (this.desired) this.scheduleReconnect();
    });
    socket.addEventListener('error', () => {
      if (socket.readyState === WebSocket.OPEN) socket.close(1011, 'transport error');
    });
  }

  private handleMessage(payload: string) {
    let frame: ServerFrame;
    try {
      frame = JSON.parse(payload) as ServerFrame;
    } catch {
      return;
    }
    this.detectSequenceGap(frame.sequence);
    if (
      frame.type === 'ERROR' &&
      (frame.code === 'REALTIME-AUTH-FAILED' || frame.code === 'REALTIME-AUTH-REQUIRED')
    ) {
      this.options.onAuthenticationFailed(frame.message ?? '实时连接认证已失效');
      this.disconnect();
      return;
    }
    if (frame.type === 'ACK' && frame.action === 'AUTH') {
      this.authenticated = true;
      this.replaySubscriptions();
      this.scheduleHeartbeat();
      return;
    }
    if (frame.type === 'PONG') {
      this.clearHeartbeatTimeoutTimer();
      this.scheduleHeartbeat();
      return;
    }
    if (frame.type === 'ACK' && frame.action === 'RESYNC') this.resyncPending = false;
    if (frame.type === 'SNAPSHOT') this.applySnapshot(frame);
    if (frame.type === 'DATA') this.applyData(frame);
  }

  private detectSequenceGap(sequence: number | undefined) {
    if (!sequence || sequence < 1) return;
    if (this.lastServerSequence > 0 && sequence !== this.lastServerSequence + 1 && !this.resyncPending) {
      this.resyncPending = true;
      this.sendAction('RESYNC');
    }
    this.lastServerSequence = sequence;
  }

  private replaySubscriptions() {
    this.propertyReferences.forEach((references, deviceId) => {
      this.sendTopic('SUBSCRIBE', deviceId, [...references.keys()]);
    });
  }

  private applySnapshot(frame: ServerFrame) {
    if (!frame.deviceId || !Array.isArray(frame.items)) return;
    frame.items.forEach(item => {
      if (!item.propertyId || typeof item.value !== 'number') return;
      this.latestValues.set(this.latestKey(frame.deviceId!, item.propertyId), {
        deviceId: frame.deviceId!,
        propertyId: item.propertyId,
        value: item.value,
        occurredAt: item.occurredAt,
        receivedAt: item.receivedAt,
        quality: item.quality,
        eventId: item.eventId
      });
    });
    this.queueListeners(frame.deviceId, new Set(frame.items.map(item => item.propertyId).filter(Boolean) as string[]));
  }

  private applyData(frame: ServerFrame) {
    if (!frame.deviceId || !frame.values) return;
    const changed = new Set<string>();
    Object.entries(frame.values).forEach(([propertyId, value]) => {
      if (typeof value !== 'number') return;
      changed.add(propertyId);
      this.latestValues.set(this.latestKey(frame.deviceId!, propertyId), {
        deviceId: frame.deviceId!,
        propertyId,
        value,
        occurredAt: frame.occurredAt,
        receivedAt: frame.receivedAt,
        quality: frame.quality,
        eventId: frame.eventId
      });
    });
    this.queueListeners(frame.deviceId, changed);
  }

  private queueListeners(deviceId: string, changed: ReadonlySet<string>) {
    this.registrations.forEach(registration => {
      if (
        registration.deviceId === deviceId &&
        [...registration.properties].some(propertyId => changed.has(propertyId))
      ) {
        this.pendingRegistrations.add(registration);
      }
    });
    if (this.flushPending || this.pendingRegistrations.size === 0) return;
    this.flushPending = true;
    queueMicrotask(() => this.flushListeners());
  }

  private flushListeners() {
    this.flushPending = false;
    const registrations = [...this.pendingRegistrations];
    this.pendingRegistrations.clear();
    registrations.forEach(registration => {
      if (!this.registrations.has(registration)) return;
      const values = [...registration.properties]
        .map(propertyId => this.getLatest(registration.deviceId, propertyId))
        .filter((value): value is RealtimeLatestValue => Boolean(value));
      registration.listener({ values });
    });
  }

  private sendTopic(action: 'SUBSCRIBE' | 'UNSUBSCRIBE', deviceId: string, properties: string[]) {
    this.sendRaw({
      action,
      requestId: this.nextRequestId(),
      topics: [{ type: 'DEVICE_LATEST', ids: [deviceId], properties }]
    });
  }

  private sendAction(action: 'RESYNC' | 'PING') {
    this.sendRaw({ action, requestId: this.nextRequestId() });
  }

  private sendRaw(message: Record<string, unknown>) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    this.socket.send(JSON.stringify(message));
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    const exponential = Math.min(
      this.options.reconnectMaxMillis,
      this.options.reconnectBaseMillis * 2 ** this.reconnectAttempt
    );
    const jitter = Math.floor(exponential * 0.2 * this.options.random());
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.desired) this.openSocket();
    }, exponential + jitter);
  }

  private scheduleHeartbeat() {
    this.clearHeartbeatTimer();
    this.heartbeatTimer = setTimeout(() => {
      this.heartbeatTimer = null;
      if (!this.authenticated || !this.socket || this.socket.readyState !== WebSocket.OPEN) return;
      const socket = this.socket;
      this.sendAction('PING');
      this.clearHeartbeatTimeoutTimer();
      this.heartbeatTimeoutTimer = setTimeout(() => {
        this.heartbeatTimeoutTimer = null;
        if (this.socket === socket && socket.readyState === WebSocket.OPEN) {
          socket.close(4000, 'heartbeat timeout');
        }
      }, this.options.heartbeatTimeoutMillis);
    }, this.options.heartbeatMillis);
  }

  private clearReconnectTimer() {
    if (!this.reconnectTimer) return;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  private clearHeartbeatTimer() {
    if (this.heartbeatTimer) {
      clearTimeout(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.clearHeartbeatTimeoutTimer();
  }

  private clearHeartbeatTimeoutTimer() {
    if (!this.heartbeatTimeoutTimer) return;
    clearTimeout(this.heartbeatTimeoutTimer);
    this.heartbeatTimeoutTimer = null;
  }

  private nextRequestId() {
    this.requestSequence += 1;
    return `rt-${this.requestSequence}`;
  }

  private latestKey(deviceId: string, propertyId: string) {
    return `${deviceId}\u0000${propertyId}`;
  }
}
