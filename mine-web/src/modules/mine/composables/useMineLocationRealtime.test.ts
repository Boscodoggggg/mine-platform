import { effectScope } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import type { CurrentLocation } from '../api/mine-api';
import type { RealtimeConnectionState, RealtimeLocationBatch } from '@/realtime';
import { useMineLocationRealtime } from './useMineLocationRealtime';

describe('useMineLocationRealtime', () => {
  it('应先读取当前位置快照再建立矿区实时订阅', async () => {
    const calls: string[] = [];
    const realtime = { listener: null as ((batch: RealtimeLocationBatch) => void) | null };
    const release = vi.fn();
    const scope = effectScope();
    const state = scope.run(() =>
      useMineLocationRealtime({
        loadCurrent: async siteId => {
          calls.push(`snapshot:${siteId}`);
          return currentSnapshot(siteId, [currentItem({ siteId })]);
        },
        subscribe: (siteId, callback) => {
          calls.push(`subscribe:${siteId}`);
          realtime.listener = callback;
          return release;
        },
        subscribeConnectionState: callback => {
          callback('CONNECTED');
          return vi.fn();
        }
      })
    )!;

    await state.selectSite('site-1');
    expect(calls).toEqual(['snapshot:site-1', 'subscribe:site-1']);
    expect(state.locations.value[0]).toMatchObject({ tagCode: 'tag-001', ingestSequence: 10 });
    expect(state.status.value).toBe('LIVE');

    realtime.listener?.({
      siteId: 'site-1',
      mode: 'UPDATE',
      items: [realtimeItem({ sourceSequence: 11, localX: 121 })]
    });
    expect(state.locations.value[0]).toMatchObject({ localX: 121, ingestSequence: 11 });

    scope.stop();
    expect(release).toHaveBeenCalledOnce();
  });

  it('切换矿区应立即清理旧订阅且忽略迟到快照', async () => {
    const releases = [vi.fn(), vi.fn()];
    let resolveFirst!: (value: CurrentLocation) => void;
    const first = new Promise<CurrentLocation>(resolve => {
      resolveFirst = resolve;
    });
    const loadCurrent = vi
      .fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(currentSnapshot('site-2', [currentItem({ siteId: 'site-2', tagId: 'tag-2' })]));
    const subscribe = vi
      .fn()
      .mockImplementationOnce(() => releases[0])
      .mockImplementationOnce(() => releases[1]);
    const scope = effectScope();
    const state = scope.run(() =>
      useMineLocationRealtime({ loadCurrent, subscribe, subscribeConnectionState: () => vi.fn() })
    )!;

    const firstSelection = state.selectSite('site-1');
    const secondSelection = state.selectSite('site-2');
    await secondSelection;
    resolveFirst(currentSnapshot('site-1', [currentItem({ siteId: 'site-1', tagCode: 'late-tag' })]));
    await firstSelection;

    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(subscribe).toHaveBeenCalledWith('site-2', expect.any(Function));
    expect(state.locations.value.map(item => item.tagCode)).not.toContain('late-tag');

    await state.selectSite(null);
    expect(releases[0]).toHaveBeenCalledOnce();
    expect(state.locations.value).toEqual([]);
    scope.stop();
  });

  it('弱网状态应可见且旧摄取序号不能倒退当前位置', async () => {
    let connectionListener!: (state: RealtimeConnectionState) => void;
    let locationListener!: (batch: RealtimeLocationBatch) => void;
    const scope = effectScope();
    const state = scope.run(() =>
      useMineLocationRealtime({
        loadCurrent: async () => currentSnapshot('site-1', [currentItem()]),
        subscribe: (_siteId, listener) => {
          locationListener = listener;
          return vi.fn();
        },
        subscribeConnectionState: listener => {
          connectionListener = listener;
          listener('CONNECTED');
          return vi.fn();
        }
      })
    )!;
    await state.selectSite('site-1');

    connectionListener('RECONNECTING');
    expect(state.connectionState.value).toBe('RECONNECTING');
    expect(state.status.value).toBe('RECONNECTING');
    locationListener({
      siteId: 'site-1',
      mode: 'UPDATE',
      items: [realtimeItem({ sourceSequence: 9, localX: 1 })]
    });
    expect(state.locations.value[0]?.localX).toBe(120);

    connectionListener('CONNECTED');
    expect(state.status.value).toBe('LIVE');
    scope.stop();
  });
});

function currentSnapshot(siteId: string, items: CurrentLocation['items']): CurrentLocation {
  return { siteId, watermark: 10, source: 'REDIS', truncated: false, items };
}

function currentItem(overrides: Partial<CurrentLocation['items'][number]> = {}): CurrentLocation['items'][number] {
  return {
    eventId: 'event-1', organizationId: 'organization-1', siteId: 'site-1', sourceDeviceId: 'device-1',
    tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1', vehicleId: 'vehicle-1', personId: null,
    ingestSequence: 10, occurredAt: '2026-07-18T00:00:00Z', receivedAt: '2026-07-18T00:00:01Z',
    localX: 120, localY: 88, localZ: null, speedMetersPerSecond: 4.5, headingDegrees: 90,
    accuracyMeters: 0.5, qualityStatus: 'GOOD', qualityReason: null, ...overrides
  };
}

function realtimeItem(overrides: Record<string, unknown> = {}) {
  const { ingestSequence: _ingestSequence, ...item } = currentItem();
  return { ...item, sourceSequence: 10, ...overrides };
}
