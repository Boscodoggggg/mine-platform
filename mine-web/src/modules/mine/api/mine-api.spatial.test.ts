import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as mineApi from './mine-api';

const client = vi.hoisted(() => ({ request: vi.fn() }));

vi.mock('@/api/client', () => client);

type ApiFunction = (...args: unknown[]) => unknown;

function apiFunction(name: string): ApiFunction | undefined {
  return (mineApi as unknown as Record<string, ApiFunction>)[name];
}

describe('mine spatial api', () => {
  beforeEach(() => {
    client.request.mockReset().mockResolvedValue({});
  });

  it('应使用矿区作用域路径封装对象、草稿、发布、事件和重放请求', async () => {
    const getMineRoutes = apiFunction('getMineRoutes');
    const updateMineGeofenceVersion = apiFunction('updateMineGeofenceVersion');
    const publishMineRouteVersion = apiFunction('publishMineRouteVersion');
    const getMineSpatialEvents = apiFunction('getMineSpatialEvents');
    const createMineSpatialReplay = apiFunction('createMineSpatialReplay');
    const functions = [
      apiFunction('getMineWaypoints'),
      apiFunction('createMineWaypoint'),
      apiFunction('archiveMineWaypoint'),
      apiFunction('getMineWaypointVersions'),
      apiFunction('createMineWaypointVersion'),
      apiFunction('updateMineWaypointVersion'),
      apiFunction('publishMineWaypointVersion'),
      getMineRoutes,
      apiFunction('createMineRoute'),
      apiFunction('archiveMineRoute'),
      apiFunction('getMineRouteVersions'),
      apiFunction('createMineRouteVersion'),
      apiFunction('updateMineRouteVersion'),
      publishMineRouteVersion,
      apiFunction('getMineGeofences'),
      apiFunction('createMineGeofence'),
      apiFunction('archiveMineGeofence'),
      apiFunction('getMineGeofenceVersions'),
      apiFunction('createMineGeofenceVersion'),
      updateMineGeofenceVersion,
      apiFunction('publishMineGeofenceVersion'),
      getMineSpatialEvents,
      apiFunction('getMineSpatialEvent'),
      createMineSpatialReplay
    ];
    if (functions.some(item => typeof item !== 'function')) {
      expect(functions.every(item => typeof item === 'function')).toBe(true);
      return;
    }

    await getMineRoutes!('site/1', { keyword: 'haul', status: 'ACTIVE', page: 2, size: 20 } as never);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/routes?keyword=haul&status=ACTIVE&page=2&size=20'
    );

    await updateMineGeofenceVersion!('site/1', 'geo/1', 'version/1', { name: 'North wall', version: 7 } as never);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/geofences/geo%2F1/versions/version%2F1',
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ name: 'North wall', version: 7 }) })
    );

    await publishMineRouteVersion!('site/1', 'route/1', 'version/1', 7 as never);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/routes/route%2F1/versions/version%2F1/publish',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ version: 7 }) })
    );

    await getMineSpatialEvents!('site/1', {
      zoneType: 'GEOFENCE',
      eventType: 'ENTER',
      watermark: 18,
      pageSize: 50
    } as never);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/spatial-events?zoneType=GEOFENCE&eventType=ENTER&watermark=18&pageSize=50'
    );

    await createMineSpatialReplay!(
      'site/1',
      { zoneType: 'GEOFENCE', zoneVersionId: 'version/1' } as never,
      'replay-key' as never
    );
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/spatial-events/replays',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Idempotency-Key': 'replay-key' }
      })
    );
  });
});
