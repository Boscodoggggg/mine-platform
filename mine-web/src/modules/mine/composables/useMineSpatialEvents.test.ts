import { effectScope } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SpatialEventPage } from '../api/mine-api';
import type { RealtimeSpatialNotification } from '@/realtime';
import { useMineSpatialEvents } from './useMineSpatialEvents';

describe('useMineSpatialEvents', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('应先建立实时订阅再合并快照，并在连接序号断档后重同步', async () => {
    let listener!: (notification: RealtimeSpatialNotification) => void;
    const release = vi.fn();
    const loadEvents = vi
      .fn()
      .mockResolvedValueOnce(eventPage([eventItem({ eventSequence: 40 })]))
      .mockResolvedValueOnce(
        eventPage([eventItem({ eventId: 'live-event', eventSequence: 41, occurredAt: '2026-07-18T00:00:01Z' })], 41)
      )
      .mockResolvedValueOnce(
        eventPage(
          [
            eventItem({ eventId: 'event-2', eventSequence: 42, occurredAt: '2026-07-18T00:00:02Z' }),
            eventItem({ eventId: 'live-event', eventSequence: 41, occurredAt: '2026-07-18T00:00:01Z' })
          ],
          42
        )
      );
    const scope = effectScope();
    const state = scope.run(() =>
      useMineSpatialEvents({
        loadRegions: vi.fn().mockResolvedValue(regionSnapshot([geofenceRegion()])),
        loadEvents,
        subscribe: (siteId, callback) => {
          expect(siteId).toBe('site-1');
          listener = callback;
          return release;
        }
      })
    )!;

    const selecting = state.selectSite('site-1');
    listener({ mode: 'UPDATE', siteId: 'site-1', item: realtimeEvent() });
    await selecting;

    expect(state.regionStatus.value).toBe('READY');
    expect(state.eventStatus.value).toBe('READY');
    expect(state.regions.value).toEqual([expect.objectContaining({ zoneId: 'zone-1' })]);
    expect(state.events.value.map(item => item.eventId)).toEqual(['live-event', 'event-1']);
    expect(state.lastEvent.value).toMatchObject({ eventType: 'ENTER', evidence: { qualityStatus: 'GOOD' } });

    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    await vi.waitFor(() => expect(loadEvents).toHaveBeenCalledTimes(2));
    await Promise.resolve();
    expect(state.eventStatus.value).toBe('LIVE');

    listener({ mode: 'RESYNC_REQUIRED', siteId: 'site-1' });
    listener({ mode: 'RESYNC_REQUIRED', siteId: 'site-1' });
    await vi.waitFor(() => expect(loadEvents).toHaveBeenCalledTimes(3));
    await Promise.resolve();

    expect(state.eventStatus.value).toBe('LIVE');
    expect(state.events.value.map(item => item.eventId)).toEqual(['event-2', 'live-event']);
    expect(state.events.value[1]?.eventSequence).toBe(41);
    expect(state.events.value[1]?.evidence?.zoneContentHash).toMatch(/^sha256:/);
    scope.stop();
    expect(release).toHaveBeenCalledOnce();
  });

  it('区域服务失败时应只降级区域层并继续接收空间事件', async () => {
    let listener!: (notification: RealtimeSpatialNotification) => void;
    const state = effectScope().run(() =>
      useMineSpatialEvents({
        loadRegions: vi.fn().mockRejectedValue(new Error('region unavailable')),
        loadEvents: vi.fn().mockResolvedValue(eventPage([])),
        subscribe: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;

    await state.selectSite('site-1');
    expect(state.regionStatus.value).toBe('DEGRADED');
    expect(state.regionError.value).toBeInstanceOf(Error);
    expect(state.eventStatus.value).toBe('READY');

    listener({ mode: 'UPDATE', siteId: 'site-1', item: realtimeEvent({ eventType: 'DWELL' }) });
    expect(state.events.value).toHaveLength(1);
    expect(state.events.value[0]?.eventType).toBe('DWELL');
  });

  it('空间订阅失败应显式降级并在后续 ACK 后恢复实时状态', async () => {
    let listener!: (notification: RealtimeSpatialNotification) => void;
    const loadEvents = vi.fn().mockResolvedValue(eventPage([]));
    const state = effectScope().run(() =>
      useMineSpatialEvents({
        loadRegions: vi.fn().mockResolvedValue(regionSnapshot([])),
        loadEvents,
        subscribe: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;

    await state.selectSite('site-1');
    listener({
      mode: 'SUBSCRIPTION_FAILED',
      siteId: 'site-1',
      code: 'REALTIME-LIMIT-EXCEEDED'
    });
    expect(state.eventStatus.value).toBe('DEGRADED');
    expect(state.eventError.value).toBeInstanceOf(Error);

    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    await vi.waitFor(() => expect(loadEvents).toHaveBeenCalledTimes(2));
    expect(state.eventStatus.value).toBe('LIVE');
    expect(state.eventError.value).toBeNull();
  });

  it('断档恢复的新快照先返回时迟到的初始快照不得回退事件水位', async () => {
    let listener!: (notification: RealtimeSpatialNotification) => void;
    let resolveInitial!: (page: SpatialEventPage) => void;
    let resolveRecovery!: (page: SpatialEventPage) => void;
    const initial = new Promise<SpatialEventPage>(resolve => {
      resolveInitial = resolve;
    });
    const recovery = new Promise<SpatialEventPage>(resolve => {
      resolveRecovery = resolve;
    });
    const loadEvents = vi.fn().mockReturnValueOnce(initial).mockReturnValueOnce(recovery);
    const state = effectScope().run(() =>
      useMineSpatialEvents({
        loadRegions: vi.fn().mockResolvedValue(regionSnapshot([])),
        loadEvents,
        subscribe: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;

    const selecting = state.selectSite('site-1');
    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    await vi.waitFor(() => expect(loadEvents).toHaveBeenCalledTimes(2));
    resolveRecovery(eventPage([eventItem({ eventId: 'event-42', eventSequence: 42 })], 42));
    await vi.waitFor(() => expect(state.eventWatermark.value).toBe(42));
    resolveInitial(eventPage([eventItem({ eventId: 'event-40', eventSequence: 40 })], 40));
    await selecting;

    expect(state.eventWatermark.value).toBe(42);
    expect(state.events.value.map(item => item.eventId)).toEqual(['event-42']);
  });

  it('矿区切换应释放旧订阅并忽略迟到快照，禁用能力不得发起请求', async () => {
    let resolveFirst!: (page: SpatialEventPage) => void;
    const firstPage = new Promise<SpatialEventPage>(resolve => {
      resolveFirst = resolve;
    });
    const releases = [vi.fn(), vi.fn()];
    const loadEvents = vi.fn().mockReturnValueOnce(firstPage).mockResolvedValueOnce(eventPage([]));
    const loadRegions = vi.fn().mockResolvedValue(regionSnapshot([]));
    const subscribe = vi
      .fn()
      .mockImplementationOnce(() => releases[0])
      .mockImplementationOnce(() => releases[1]);
    const state = effectScope().run(() => useMineSpatialEvents({ loadEvents, loadRegions, subscribe }))!;

    const first = state.selectSite('site-1');
    await state.selectSite('site-2', { regions: false, events: true });
    resolveFirst(eventPage([eventItem({ siteId: 'site-1' })]));
    await first;

    expect(releases[0]).toHaveBeenCalledOnce();
    expect(state.events.value).toEqual([]);
    expect(state.regionStatus.value).toBe('DISABLED');
    expect(loadRegions).toHaveBeenCalledTimes(1);

    await state.selectSite('site-3', { regions: false, events: false });
    expect(releases[1]).toHaveBeenCalledOnce();
    expect(loadEvents).toHaveBeenCalledTimes(2);
    expect(state.eventStatus.value).toBe('DISABLED');
  });

  it('到达服务端返回的有效期边界后应自动刷新聚合区域快照', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-18T00:00:00Z'));
    const loadRegions = vi
      .fn()
      .mockResolvedValueOnce(regionSnapshot([geofenceRegion()], '2026-07-18T00:00:01Z'))
      .mockResolvedValueOnce(regionSnapshot([]));
    const state = effectScope().run(() =>
      useMineSpatialEvents({
        loadRegions,
        loadEvents: vi.fn().mockResolvedValue(eventPage([])),
        subscribe: () => vi.fn()
      })
    )!;

    await state.selectSite('site-1', { regions: true, events: false });
    expect(state.regions.value).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(1_100);

    expect(loadRegions).toHaveBeenCalledTimes(2);
    expect(state.regions.value).toEqual([]);
    state.stop();
  });

  it('区域有效期刷新应使用服务端相对时长而不受客户端时钟偏差影响', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-18T00:10:00Z'));
    const loadRegions = vi
      .fn()
      .mockResolvedValueOnce(regionSnapshot([geofenceRegion()], '2026-07-18T00:00:01Z'))
      .mockResolvedValueOnce(regionSnapshot([]));
    const state = effectScope().run(() =>
      useMineSpatialEvents({
        loadRegions,
        loadEvents: vi.fn().mockResolvedValue(eventPage([])),
        subscribe: () => vi.fn()
      })
    )!;

    await state.selectSite('site-1', { regions: true, events: false });
    await vi.advanceTimersByTimeAsync(500);
    expect(loadRegions).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(600);
    expect(loadRegions).toHaveBeenCalledTimes(2);
    state.stop();
  });

  it('同矿区刷新失败应分层保留最后正确空间数据与更新时间', async () => {
    const loadRegions = vi
      .fn()
      .mockResolvedValueOnce(regionSnapshot([geofenceRegion()]))
      .mockRejectedValueOnce(new Error('region unavailable'));
    const loadEvents = vi
      .fn()
      .mockResolvedValueOnce(eventPage([eventItem()]))
      .mockRejectedValueOnce(new Error('event unavailable'));
    const state = effectScope().run(() =>
      useMineSpatialEvents({ loadRegions, loadEvents, subscribe: () => vi.fn() })
    )!;

    await state.selectSite('site-1');
    expect(state.regionUpdatedAt.value).toBe('2026-07-18T00:00:00Z');
    expect(state.eventUpdatedAt.value).toBe('2026-07-18T00:00:00Z');

    await state.selectSite('site-1');

    expect(state.regionStatus.value).toBe('DEGRADED');
    expect(state.eventStatus.value).toBe('DEGRADED');
    expect(state.regions.value).toHaveLength(1);
    expect(state.events.value).toHaveLength(1);
    expect(state.regionUpdatedAt.value).toBe('2026-07-18T00:00:00Z');
    expect(state.eventUpdatedAt.value).toBe('2026-07-18T00:00:00Z');
  });
});

function eventPage(items: SpatialEventPage['items'], watermark = 40): SpatialEventPage {
  return {
    siteId: 'site-1',
    from: '2026-07-17T00:00:00Z',
    to: '2026-07-18T00:00:00Z',
    watermark,
    nextCursor: null,
    items
  };
}

function eventItem(overrides: Partial<SpatialEventPage['items'][number]> = {}): SpatialEventPage['items'][number] {
  return {
    eventId: 'event-1',
    eventSequence: 40,
    organizationId: 'organization-1',
    siteId: 'site-1',
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    tagId: 'tag-1',
    vehicleId: 'vehicle-1',
    personId: null,
    eventType: 'ENTER',
    previousState: 'OUTSIDE',
    currentState: 'INSIDE',
    confirmationPoints: 3,
    ingestSequence: 11,
    runtimeRevision: 5,
    occurredAt: '2026-07-18T00:00:00Z',
    ...overrides
  };
}

function realtimeEvent(overrides: Record<string, unknown> = {}) {
  return {
    ...eventItem({ eventId: 'live-event', eventSequence: 41, occurredAt: '2026-07-18T00:00:01Z' }),
    anchorLocationEventId: 'location-event-1',
    evidence: {
      localX: 120,
      localY: 88,
      localZ: null,
      classification: 'INSIDE' as const,
      qualityStatus: 'GOOD' as const,
      qualityReason: null,
      zoneContentHash: `sha256:${'a'.repeat(64)}`
    },
    ...overrides
  };
}

function geofenceRegion() {
  return {
    zoneType: 'GEOFENCE' as const,
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    coordinateSystemId: 'coordinate-1',
    name: '北区禁入围栏',
    contentHash: `sha256:${'a'.repeat(64)}`,
    shape: 'POLYGON' as const,
    vertices: [
      { x: 100, y: 60 },
      { x: 160, y: 60 },
      { x: 160, y: 120 }
    ]
  };
}

function regionSnapshot(regions: ReturnType<typeof geofenceRegion>[], nextChangeAt: string | null = null) {
  return {
    siteId: 'site-1',
    evaluatedAt: '2026-07-18T00:00:00Z',
    nextChangeAt,
    regions
  };
}
