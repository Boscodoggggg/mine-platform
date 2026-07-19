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

export type RealtimeConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'AUTHENTICATING'
  | 'CONNECTED'
  | 'RECONNECTING';

export interface RealtimeLocationItem {
  eventId: string;
  organizationId: string;
  siteId: string;
  sourceDeviceId: string;
  tagId: string;
  tagCode: string;
  coordinateSystemId: string;
  vehicleId: string | null;
  personId: string | null;
  ingestSequence?: number;
  sourceSequence?: number;
  occurredAt: string;
  receivedAt: string;
  localX: number;
  localY: number;
  localZ: number | null;
  speedMetersPerSecond: number | null;
  headingDegrees: number | null;
  accuracyMeters: number | null;
  qualityStatus: 'GOOD' | 'DEGRADED';
  qualityReason: string | null;
}

export interface RealtimeLocationBatch {
  siteId: string;
  mode: 'SNAPSHOT' | 'UPDATE';
  items: readonly RealtimeLocationItem[];
  watermark?: number;
  source?: 'REDIS' | 'TIMESCALEDB';
  truncated?: boolean;
}

export type RealtimeLocationListener = (batch: RealtimeLocationBatch) => void;

export interface RealtimeSpatialEvidence {
  localX: number;
  localY: number;
  localZ: number | null;
  classification: 'INSIDE' | 'OUTSIDE' | 'BOUNDARY' | 'HEIGHT_UNKNOWN';
  qualityStatus: 'GOOD' | 'DEGRADED';
  qualityReason: string | null;
  zoneContentHash: string;
}

export interface RealtimeSpatialEventItem {
  eventId: string;
  organizationId: string;
  siteId: string;
  zoneType: 'WAYPOINT' | 'GEOFENCE';
  zoneId: string;
  zoneVersionId: string;
  tagId: string;
  vehicleId: string | null;
  personId: string | null;
  eventType: 'ENTER' | 'EXIT' | 'DWELL';
  anchorLocationEventId: string;
  previousState: 'OUTSIDE' | 'INSIDE';
  currentState: 'OUTSIDE' | 'INSIDE';
  confirmationPoints: number;
  ingestSequence: number;
  runtimeRevision: number;
  occurredAt: string;
  evidence: RealtimeSpatialEvidence;
}

export type RealtimeSpatialNotification =
  | { mode: 'UPDATE'; siteId: string; item: RealtimeSpatialEventItem }
  | { mode: 'SUBSCRIBED'; siteId: string }
  | { mode: 'SUBSCRIPTION_FAILED'; siteId: string; code: string }
  | { mode: 'RESYNC_REQUIRED'; siteId: string };

export type RealtimeSpatialListener = (notification: RealtimeSpatialNotification) => void;

export interface RealtimeHubOptions {
  url: string;
  token: () => string;
  createSocket?: (url: string) => RealtimeSocket;
  random?: () => number;
  reconnectBaseMillis?: number;
  reconnectMaxMillis?: number;
  heartbeatMillis?: number;
  heartbeatTimeoutMillis?: number;
  spatialSubscriptionTimeoutMillis?: number;
  spatialSubscriptionRetryMillis?: number;
  spatialSubscriptionMaxAttempts?: number;
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

interface LocationRegistration {
  siteId: string;
  listener: RealtimeLocationListener;
}

interface SpatialRegistration {
  siteId: string;
  listener: RealtimeSpatialListener;
}

interface PendingSpatialSubscription {
  siteId: string;
  timeoutTimer: ReturnType<typeof setTimeout>;
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
  topic?: string;
  siteId?: string;
  watermark?: number;
  source?: 'REDIS' | 'TIMESCALEDB';
  truncated?: boolean;
  values?: Record<string, number>;
  items?: Array<{
    propertyId?: string;
    value?: number;
    occurredAt?: string;
    receivedAt?: string;
    quality?: string;
    eventId?: string;
  }>;
  item?: unknown;
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
      | 'spatialSubscriptionTimeoutMillis'
      | 'spatialSubscriptionRetryMillis'
      | 'spatialSubscriptionMaxAttempts'
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
      | 'spatialSubscriptionTimeoutMillis'
      | 'spatialSubscriptionRetryMillis'
      | 'spatialSubscriptionMaxAttempts'
      | 'onAuthenticationFailed'
    >;

  private readonly registrations = new Set<Registration>();
  private readonly propertyReferences = new Map<string, Map<string, number>>();
  private readonly latestValues = new Map<string, RealtimeLatestValue>();
  private readonly pendingRegistrations = new Set<Registration>();
  private readonly locationRegistrations = new Set<LocationRegistration>();
  private readonly locationReferences = new Map<string, number>();
  private readonly latestLocations = new Map<string, Map<string, RealtimeLocationItem>>();
  private readonly spatialRegistrations = new Set<SpatialRegistration>();
  private readonly spatialReferences = new Map<string, number>();
  private readonly pendingSpatialSubscriptions = new Map<string, PendingSpatialSubscription>();
  private readonly activeSpatialSubscriptions = new Set<string>();
  private readonly spatialSubscriptionAttempts = new Map<string, number>();
  private readonly spatialSubscriptionFailures = new Map<string, string>();
  private readonly spatialSubscriptionRetryTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly pendingLocationBatches = new Map<
    string,
    {
      mode: 'SNAPSHOT' | 'UPDATE';
      items: Map<string, RealtimeLocationItem>;
      watermark?: number;
      source?: 'REDIS' | 'TIMESCALEDB';
      truncated?: boolean;
    }
  >();
  private readonly connectionListeners = new Set<(state: RealtimeConnectionState) => void>();
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
  private locationFlushPending = false;
  private connectionState: RealtimeConnectionState = 'DISCONNECTED';

  constructor(options: RealtimeHubOptions) {
    this.options = {
      ...options,
      createSocket: options.createSocket ?? (url => new WebSocket(url)),
      random: options.random ?? Math.random,
      reconnectBaseMillis: options.reconnectBaseMillis ?? 1000,
      reconnectMaxMillis: options.reconnectMaxMillis ?? 30_000,
      heartbeatMillis: options.heartbeatMillis ?? 25_000,
      heartbeatTimeoutMillis: options.heartbeatTimeoutMillis ?? 10_000,
      spatialSubscriptionTimeoutMillis: options.spatialSubscriptionTimeoutMillis ?? 10_000,
      spatialSubscriptionRetryMillis: options.spatialSubscriptionRetryMillis ?? 1000,
      spatialSubscriptionMaxAttempts: options.spatialSubscriptionMaxAttempts ?? 3,
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
    this.resetSpatialSubscriptionState();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'client disconnect');
    this.setConnectionState('DISCONNECTED');
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

  /** 订阅单个矿区的位置快照和增量，多个页面共享同一服务端订阅。 */
  subscribeMineLocation(siteId: string, listener: RealtimeLocationListener): () => void {
    const normalizedSiteId = siteId.trim();
    if (!normalizedSiteId) throw new Error('矿区不能为空');
    const registration: LocationRegistration = { siteId: normalizedSiteId, listener };
    this.locationRegistrations.add(registration);
    const referenceCount = this.locationReferences.get(normalizedSiteId) ?? 0;
    this.locationReferences.set(normalizedSiteId, referenceCount + 1);
    this.connect();
    if (referenceCount === 0 && this.authenticated) {
      this.sendLocationTopic('SUBSCRIBE', normalizedSiteId);
    }

    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.locationRegistrations.delete(registration);
      const count = this.locationReferences.get(normalizedSiteId) ?? 0;
      if (count <= 1) {
        this.locationReferences.delete(normalizedSiteId);
        this.latestLocations.delete(normalizedSiteId);
        this.pendingLocationBatches.delete(normalizedSiteId);
        if (this.authenticated) this.sendLocationTopic('UNSUBSCRIBE', normalizedSiteId);
      } else {
        this.locationReferences.set(normalizedSiteId, count - 1);
      }
    };
  }

  /** 订阅单个矿区的空间业务事件，多个页面共享同一服务端订阅。 */
  subscribeMineSpatialEvent(siteId: string, listener: RealtimeSpatialListener): () => void {
    const normalizedSiteId = siteId.trim();
    if (!normalizedSiteId) throw new Error('矿区不能为空');
    const registration: SpatialRegistration = { siteId: normalizedSiteId, listener };
    this.spatialRegistrations.add(registration);
    const referenceCount = this.spatialReferences.get(normalizedSiteId) ?? 0;
    this.spatialReferences.set(normalizedSiteId, referenceCount + 1);
    this.connect();
    if (referenceCount === 0 && this.authenticated) {
      this.sendSpatialTopic('SUBSCRIBE', normalizedSiteId);
    }
    if (this.activeSpatialSubscriptions.has(normalizedSiteId)) {
      listener({ mode: 'SUBSCRIBED', siteId: normalizedSiteId });
    } else {
      const failureCode = this.spatialSubscriptionFailures.get(normalizedSiteId);
      if (failureCode) {
        listener({ mode: 'SUBSCRIPTION_FAILED', siteId: normalizedSiteId, code: failureCode });
      }
    }

    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.spatialRegistrations.delete(registration);
      const count = this.spatialReferences.get(normalizedSiteId) ?? 0;
      if (count <= 1) {
        this.spatialReferences.delete(normalizedSiteId);
        this.clearSpatialSubscriptionState(normalizedSiteId);
        if (this.authenticated) this.sendSpatialTopic('UNSUBSCRIBE', normalizedSiteId);
      } else {
        this.spatialReferences.set(normalizedSiteId, count - 1);
      }
    };
  }

  /** 订阅连接状态；注册时立即返回当前状态。 */
  subscribeConnectionState(listener: (state: RealtimeConnectionState) => void): () => void {
    this.connectionListeners.add(listener);
    listener(this.connectionState);
    return () => this.connectionListeners.delete(listener);
  }

  /** 返回内存中的单个属性最新值。 */
  getLatest(deviceId: string, propertyId: string) {
    return this.latestValues.get(this.latestKey(deviceId, propertyId));
  }

  /** 返回矿区内按标签保存的最新位置副本。 */
  getLatestLocations(siteId: string) {
    return [...(this.latestLocations.get(siteId)?.values() ?? [])];
  }

  private openSocket() {
    this.clearReconnectTimer();
    this.setConnectionState(this.reconnectAttempt > 0 ? 'RECONNECTING' : 'CONNECTING');
    const socket = this.options.createSocket(this.options.url);
    this.socket = socket;
    this.authenticated = false;
    this.clearHeartbeatTimer();
    this.lastServerSequence = 0;
    this.resyncPending = false;
    this.resetSpatialSubscriptionState();
    socket.addEventListener('open', () => {
      if (this.socket !== socket) return;
      this.reconnectAttempt = 0;
      this.setConnectionState('AUTHENTICATING');
      this.sendRaw({ action: 'AUTH', requestId: this.nextRequestId(), token: this.options.token() });
    });
    socket.addEventListener('message', event => {
      if (this.socket !== socket) return;
      this.handleMessage((event as MessageEvent<string>).data);
    });
    socket.addEventListener('close', () => {
      if (this.socket === socket) this.socket = null;
      this.authenticated = false;
      this.resetSpatialSubscriptionState();
      this.clearHeartbeatTimer();
      if (this.desired) {
        this.setConnectionState('RECONNECTING');
        this.scheduleReconnect();
      } else {
        this.setConnectionState('DISCONNECTED');
      }
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
      this.setConnectionState('CONNECTED');
      this.replaySubscriptions();
      this.scheduleHeartbeat();
      return;
    }
    if (frame.type === 'ERROR' && frame.requestId && this.pendingSpatialSubscriptions.has(frame.requestId)) {
      this.failSpatialSubscription(frame.requestId, frame.code ?? 'REALTIME-SUBSCRIPTION-FAILED');
      return;
    }
    if (frame.type === 'ACK' && frame.action === 'SUBSCRIBE' && frame.requestId) {
      const pending = this.pendingSpatialSubscriptions.get(frame.requestId);
      if (pending) {
        this.completePendingSpatialSubscription(frame.requestId);
        if (this.spatialReferences.has(pending.siteId)) {
          this.spatialSubscriptionAttempts.delete(pending.siteId);
          this.spatialSubscriptionFailures.delete(pending.siteId);
          this.clearSpatialSubscriptionRetryTimer(pending.siteId);
          this.activeSpatialSubscriptions.add(pending.siteId);
          this.notifySpatialSubscribed(pending.siteId);
        }
      }
      return;
    }
    if (frame.type === 'PONG') {
      this.clearHeartbeatTimeoutTimer();
      this.scheduleHeartbeat();
      return;
    }
    if (frame.type === 'ACK' && frame.action === 'RESYNC') this.resyncPending = false;
    if (frame.topic === 'MINE_SITE_LOCATION') {
      if (frame.type === 'SNAPSHOT') this.applyLocationSnapshot(frame);
      if (frame.type === 'DATA') this.applyLocationData(frame);
      return;
    }
    if (frame.topic === 'MINE_SITE_SPATIAL_EVENT') {
      if (frame.type === 'DATA') this.applySpatialData(frame);
      return;
    }
    if (frame.type === 'SNAPSHOT') this.applySnapshot(frame);
    if (frame.type === 'DATA') this.applyData(frame);
  }

  private detectSequenceGap(sequence: number | undefined) {
    if (!sequence || sequence < 1) return;
    if (this.lastServerSequence > 0 && sequence !== this.lastServerSequence + 1 && !this.resyncPending) {
      this.resyncPending = true;
      this.notifySpatialResync();
      this.sendAction('RESYNC');
    }
    this.lastServerSequence = sequence;
  }

  private replaySubscriptions() {
    this.propertyReferences.forEach((references, deviceId) => {
      this.sendTopic('SUBSCRIBE', deviceId, [...references.keys()]);
    });
    this.locationReferences.forEach((_count, siteId) => {
      this.sendLocationTopic('SUBSCRIBE', siteId);
    });
    this.spatialReferences.forEach((_count, siteId) => {
      this.sendSpatialTopic('SUBSCRIBE', siteId);
    });
  }

  private applySpatialData(frame: ServerFrame) {
    if (!frame.siteId || !this.isSpatialEventItem(frame.item) || frame.item.siteId !== frame.siteId) return;
    this.spatialRegistrations.forEach(registration => {
      if (registration.siteId === frame.siteId) {
        registration.listener({ mode: 'UPDATE', siteId: frame.siteId!, item: frame.item as RealtimeSpatialEventItem });
      }
    });
  }

  private notifySpatialResync() {
    this.activeSpatialSubscriptions.forEach(siteId => {
      this.spatialRegistrations.forEach(registration => {
        if (registration.siteId === siteId) {
          registration.listener({ mode: 'RESYNC_REQUIRED', siteId });
        }
      });
    });
  }

  private notifySpatialSubscribed(siteId: string) {
    this.spatialRegistrations.forEach(registration => {
      if (registration.siteId === siteId) registration.listener({ mode: 'SUBSCRIBED', siteId });
    });
  }

  private notifySpatialSubscriptionFailed(siteId: string, code: string) {
    this.spatialRegistrations.forEach(registration => {
      if (registration.siteId === siteId) {
        registration.listener({ mode: 'SUBSCRIPTION_FAILED', siteId, code });
      }
    });
  }

  private applyLocationSnapshot(frame: ServerFrame) {
    if (!frame.siteId || !Array.isArray(frame.items)) return;
    const items = (frame.items as unknown[])
      .filter(this.isLocationItem)
      .filter(item => item.siteId === frame.siteId);
    const latest = new Map<string, RealtimeLocationItem>();
    items.forEach(item => {
      const existing = latest.get(item.tagId);
      if (!existing || this.locationSequence(item) > this.locationSequence(existing)) {
        latest.set(item.tagId, item);
      }
    });
    this.latestLocations.set(frame.siteId, latest);
    this.queueLocationBatch(frame.siteId, 'SNAPSHOT', [...latest.values()], {
      watermark: frame.watermark,
      source: frame.source,
      truncated: frame.truncated
    });
  }

  private applyLocationData(frame: ServerFrame) {
    if (!frame.siteId || !this.isLocationItem(frame.item) || frame.item.siteId !== frame.siteId) return;
    const latest = this.latestLocations.get(frame.siteId) ?? new Map<string, RealtimeLocationItem>();
    const existing = latest.get(frame.item.tagId);
    if (existing && this.locationSequence(frame.item) <= this.locationSequence(existing)) return;
    latest.set(frame.item.tagId, frame.item);
    this.latestLocations.set(frame.siteId, latest);
    this.queueLocationBatch(frame.siteId, 'UPDATE', [frame.item]);
  }

  private queueLocationBatch(
    siteId: string,
    mode: 'SNAPSHOT' | 'UPDATE',
    items: readonly RealtimeLocationItem[],
    metadata: Pick<RealtimeLocationBatch, 'watermark' | 'source' | 'truncated'> = {}
  ) {
    const existing = this.pendingLocationBatches.get(siteId);
    const pending = existing ?? { mode, items: new Map<string, RealtimeLocationItem>() };
    if (mode === 'SNAPSHOT') {
      pending.mode = 'SNAPSHOT';
      pending.items.clear();
    }
    items.forEach(item => pending.items.set(item.tagId, item));
    if (metadata.watermark !== undefined) pending.watermark = metadata.watermark;
    if (metadata.source !== undefined) pending.source = metadata.source;
    if (metadata.truncated !== undefined) pending.truncated = metadata.truncated;
    this.pendingLocationBatches.set(siteId, pending);
    if (this.locationFlushPending) return;
    this.locationFlushPending = true;
    queueMicrotask(() => this.flushLocationListeners());
  }

  private flushLocationListeners() {
    this.locationFlushPending = false;
    const batches = [...this.pendingLocationBatches.entries()];
    this.pendingLocationBatches.clear();
    batches.forEach(([siteId, pending]) => {
      const batch: RealtimeLocationBatch = {
        siteId,
        mode: pending.mode,
        items: [...pending.items.values()],
        watermark: pending.watermark,
        source: pending.source,
        truncated: pending.truncated
      };
      this.locationRegistrations.forEach(registration => {
        if (registration.siteId === siteId) registration.listener(batch);
      });
    });
  }

  private readonly isLocationItem = (value: unknown): value is RealtimeLocationItem => {
    if (!value || typeof value !== 'object') return false;
    const item = value as Partial<RealtimeLocationItem>;
    return Boolean(
      item.eventId &&
        item.siteId &&
        item.tagId &&
        item.tagCode &&
        typeof item.localX === 'number' &&
        Number.isFinite(item.localX) &&
        typeof item.localY === 'number' &&
        Number.isFinite(item.localY) &&
        this.locationSequence(item) > 0
    );
  };

  private locationSequence(item: Partial<RealtimeLocationItem>) {
    return item.sourceSequence ?? item.ingestSequence ?? 0;
  }

  private readonly isSpatialEventItem = (value: unknown): value is RealtimeSpatialEventItem => {
    if (!value || typeof value !== 'object') return false;
    const item = value as Partial<RealtimeSpatialEventItem>;
    const evidence = item.evidence as Partial<RealtimeSpatialEvidence> | undefined;
    return (
      this.isNonBlankString(item.eventId) &&
      this.isNonBlankString(item.organizationId) &&
      this.isNonBlankString(item.siteId) &&
      this.isNonBlankString(item.zoneId) &&
      this.isNonBlankString(item.zoneVersionId) &&
      this.isNonBlankString(item.tagId) &&
      this.isNonBlankString(item.anchorLocationEventId) &&
      this.isNullableString(item.vehicleId) &&
      this.isNullableString(item.personId) &&
      ['WAYPOINT', 'GEOFENCE'].includes(item.zoneType ?? '') &&
      ['ENTER', 'EXIT', 'DWELL'].includes(item.eventType ?? '') &&
      ['OUTSIDE', 'INSIDE'].includes(item.previousState ?? '') &&
      ['OUTSIDE', 'INSIDE'].includes(item.currentState ?? '') &&
      Number.isInteger(item.confirmationPoints) &&
      (item.confirmationPoints ?? 0) > 0 &&
      Number.isInteger(item.ingestSequence) &&
      (item.ingestSequence ?? 0) > 0 &&
      Number.isInteger(item.runtimeRevision) &&
      (item.runtimeRevision ?? 0) > 0 &&
      this.isNonBlankString(item.occurredAt) &&
      Number.isFinite(Date.parse(item.occurredAt)) &&
      Boolean(evidence) &&
      Number.isFinite(evidence?.localX) &&
      Number.isFinite(evidence?.localY) &&
      (evidence?.localZ === null || Number.isFinite(evidence?.localZ)) &&
      ['INSIDE', 'OUTSIDE', 'BOUNDARY', 'HEIGHT_UNKNOWN'].includes(evidence?.classification ?? '') &&
      ['GOOD', 'DEGRADED'].includes(evidence?.qualityStatus ?? '') &&
      this.isNullableString(evidence?.qualityReason) &&
      typeof evidence?.zoneContentHash === 'string' &&
      /^sha256:[0-9a-f]{64}$/.test(evidence.zoneContentHash)
    );
  };

  private isNonBlankString(value: unknown): value is string {
    return typeof value === 'string' && value.trim().length > 0;
  }

  private isNullableString(value: unknown): value is string | null {
    return value === null || typeof value === 'string';
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

  private sendLocationTopic(action: 'SUBSCRIBE' | 'UNSUBSCRIBE', siteId: string) {
    this.sendRaw({
      action,
      requestId: this.nextRequestId(),
      topics: [{ type: 'MINE_SITE_LOCATION', ids: [siteId] }]
    });
  }

  private sendSpatialTopic(action: 'SUBSCRIBE' | 'UNSUBSCRIBE', siteId: string) {
    const requestId = this.nextRequestId();
    if (action === 'SUBSCRIBE') {
      this.activeSpatialSubscriptions.delete(siteId);
      this.clearPendingSpatialSubscriptions(siteId);
      this.clearSpatialSubscriptionRetryTimer(siteId);
      this.spatialSubscriptionAttempts.set(siteId, (this.spatialSubscriptionAttempts.get(siteId) ?? 0) + 1);
      const timeoutTimer = setTimeout(() => {
        this.failSpatialSubscription(requestId, 'REALTIME-SUBSCRIPTION-TIMEOUT');
      }, this.options.spatialSubscriptionTimeoutMillis);
      this.pendingSpatialSubscriptions.set(requestId, { siteId, timeoutTimer });
    } else {
      this.clearSpatialSubscriptionState(siteId);
    }
    this.sendRaw({
      action,
      requestId,
      topics: [{ type: 'MINE_SITE_SPATIAL_EVENT', ids: [siteId] }]
    });
  }

  private clearSpatialSubscriptionState(siteId: string) {
    this.activeSpatialSubscriptions.delete(siteId);
    this.clearPendingSpatialSubscriptions(siteId);
    this.clearSpatialSubscriptionRetryTimer(siteId);
    this.spatialSubscriptionAttempts.delete(siteId);
    this.spatialSubscriptionFailures.delete(siteId);
  }

  private resetSpatialSubscriptionState() {
    this.activeSpatialSubscriptions.clear();
    this.pendingSpatialSubscriptions.forEach(pending => clearTimeout(pending.timeoutTimer));
    this.pendingSpatialSubscriptions.clear();
    this.spatialSubscriptionRetryTimers.forEach(timer => clearTimeout(timer));
    this.spatialSubscriptionRetryTimers.clear();
    this.spatialSubscriptionAttempts.clear();
    this.spatialSubscriptionFailures.clear();
  }

  private completePendingSpatialSubscription(requestId: string) {
    const pending = this.pendingSpatialSubscriptions.get(requestId);
    if (!pending) return null;
    clearTimeout(pending.timeoutTimer);
    this.pendingSpatialSubscriptions.delete(requestId);
    return pending;
  }

  private failSpatialSubscription(requestId: string, code: string) {
    const pending = this.completePendingSpatialSubscription(requestId);
    if (!pending || !this.spatialReferences.has(pending.siteId)) return;
    this.spatialSubscriptionFailures.set(pending.siteId, code);
    this.notifySpatialSubscriptionFailed(pending.siteId, code);
    const attempts = this.spatialSubscriptionAttempts.get(pending.siteId) ?? 0;
    if (!this.authenticated || attempts >= this.options.spatialSubscriptionMaxAttempts) return;
    const timer = setTimeout(() => {
      this.spatialSubscriptionRetryTimers.delete(pending.siteId);
      if (this.authenticated && this.spatialReferences.has(pending.siteId)) {
        this.sendSpatialTopic('SUBSCRIBE', pending.siteId);
      }
    }, this.options.spatialSubscriptionRetryMillis);
    this.spatialSubscriptionRetryTimers.set(pending.siteId, timer);
  }

  private clearPendingSpatialSubscriptions(siteId: string) {
    this.pendingSpatialSubscriptions.forEach((pending, requestId) => {
      if (pending.siteId !== siteId) return;
      clearTimeout(pending.timeoutTimer);
      this.pendingSpatialSubscriptions.delete(requestId);
    });
  }

  private clearSpatialSubscriptionRetryTimer(siteId: string) {
    const timer = this.spatialSubscriptionRetryTimers.get(siteId);
    if (!timer) return;
    clearTimeout(timer);
    this.spatialSubscriptionRetryTimers.delete(siteId);
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

  private setConnectionState(state: RealtimeConnectionState) {
    if (this.connectionState === state) return;
    this.connectionState = state;
    this.connectionListeners.forEach(listener => listener(state));
  }

  private nextRequestId() {
    this.requestSequence += 1;
    return `rt-${this.requestSequence}`;
  }

  private latestKey(deviceId: string, propertyId: string) {
    return `${deviceId}\u0000${propertyId}`;
  }
}
