import { expect, test } from '@playwright/test';

test('当前位置快照、实时增量与服务端抽稀轨迹应形成闭环', async ({ page }) => {
  const occurredAt = new Date().toISOString();
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({ json: { id: 'user-1', username: 'dispatcher', displayName: '调度员', passwordChangeRequired: false } })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['MINE_DISPATCHER'],
        permissions: ['mine:location:view'],
        menus: [{
          id: 'mine-root', parentId: null, name: '矿区管理', type: 'DIRECTORY', path: '/mine', componentKey: null,
          permission: null, icon: 'mdi:mine', sortOrder: 600, visible: true,
          children: [{
            id: 'mine-location', parentId: 'mine-root', name: '实时定位', type: 'MENU', path: '/mine/location',
            componentKey: 'mine-location-monitor', permission: 'mine:location:view', icon: 'mdi:map-marker-radius',
            sortOrder: 660, visible: true, children: []
          }]
        }]
      }
    })
  );
  await page.route('**/api/v1/mine/context', route =>
    route.fulfill({
      json: {
        organizations: [{
          organization: { id: 'organization-1', organizationCode: 'NORTH', name: '华北矿业', status: 'ACTIVE', archivedAt: null, createdAt: occurredAt, updatedAt: occurredAt, version: 0 },
          organizationWide: false,
          sites: [{ id: 'site-1', organizationId: 'organization-1', siteCode: 'PIT-A', name: 'A矿', timezone: 'Asia/Shanghai', description: '主采区', status: 'ACTIVE', archivedAt: null, createdAt: occurredAt, updatedAt: occurredAt, version: 0 }]
        }]
      }
    })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/vehicles\?.*$/, route =>
    route.fulfill({ json: { items: [{ id: 'vehicle-1', organizationId: 'organization-1', siteId: 'site-1', vehicleCode: 'TRUCK-01', name: '1号矿卡', vehicleType: 'HAUL_TRUCK', mineNumber: 'M-001', licensePlate: null, brand: null, model: null, ratedLoadTons: 90, status: 'ACTIVE', archivedAt: null, createdAt: occurredAt, updatedAt: occurredAt, version: 0 }], page: 1, size: 100, total: 1 } })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/people\?.*$/, route =>
    route.fulfill({ json: { items: [], page: 1, size: 100, total: 0 } })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/locations\/current\?.*$/, route =>
    route.fulfill({ json: { siteId: 'site-1', watermark: 10, source: 'REDIS', truncated: false, items: [location({ occurredAt, ingestSequence: 10 })] } })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/locations\/trajectory\?.*$/, route => {
    const { tagCode: _tagCode, ...point } = location({ occurredAt, ingestSequence: 10 });
    return route.fulfill({ json: { siteId: 'site-1', subjectType: 'VEHICLE', subjectId: 'vehicle-1', from: occurredAt, to: occurredAt, watermark: 10, nextCursor: null, sampled: true, sourcePointCount: 8600, items: [point] } });
  });
  await page.routeWebSocket('**/api/v1/realtime', socket => {
    let sequence = 0;
    socket.onMessage(message => {
      const request = JSON.parse(String(message));
      if (request.action === 'AUTH') {
        socket.send(JSON.stringify({ type: 'ACK', action: 'AUTH', requestId: request.requestId, sequence: ++sequence }));
      }
      if (request.action === 'SUBSCRIBE' && request.topics?.[0]?.type === 'MINE_SITE_LOCATION') {
        socket.send(JSON.stringify({ type: 'ACK', action: 'SUBSCRIBE', requestId: request.requestId, sequence: ++sequence }));
        socket.send(JSON.stringify({ type: 'SNAPSHOT', topic: 'MINE_SITE_LOCATION', siteId: 'site-1', watermark: 10, source: 'REDIS', truncated: false, items: [location({ occurredAt, ingestSequence: 10 })], sequence: ++sequence }));
        socket.send(JSON.stringify({ type: 'DATA', topic: 'MINE_SITE_LOCATION', siteId: 'site-1', item: location({ occurredAt, ingestSequence: undefined, sourceSequence: 11, localX: 140 }), sequence: ++sequence }));
      }
    });
  });

  await page.goto('/mine/location');
  await expect(page.getByText('矿区实时定位', { exact: true })).toBeVisible();
  await expect(page.getByText('1号矿卡 · TRUCK-01')).toBeVisible();
  await expect(page.getByText('(140, 88)')).toBeVisible();
  await page.getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('原始 8600 个点已由服务端抽稀后展示')).toBeVisible();
});

function location(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'event-1', organizationId: 'organization-1', siteId: 'site-1', sourceDeviceId: 'device-1',
    tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1', vehicleId: 'vehicle-1', personId: null,
    ingestSequence: 10, occurredAt: '2026-07-18T00:00:00Z', receivedAt: '2026-07-18T00:00:01Z',
    localX: 120, localY: 88, localZ: null, speedMetersPerSecond: 4.5, headingDegrees: 90,
    accuracyMeters: 0.5, qualityStatus: 'GOOD', qualityReason: null, ...overrides
  };
}
