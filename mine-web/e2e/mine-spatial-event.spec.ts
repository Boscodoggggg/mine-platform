import { expect, test } from '@playwright/test';

test('已发布区域、三类实时事件、断档恢复和判定证据应形成定位闭环', async ({ page }) => {
  const occurredAt = new Date().toISOString();
  let spatialSnapshotRequests = 0;
  let spatialRegionRequests = 0;
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: {
        id: 'user-1',
        username: 'dispatcher',
        displayName: '调度员',
        passwordChangeRequired: false
      }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['MINE_DISPATCHER'],
        permissions: ['mine:location:view', 'mine:spatial:view', 'mine:spatial:event:view'],
        menus: [
          {
            id: 'mine-root',
            parentId: null,
            name: '矿区管理',
            type: 'DIRECTORY',
            path: '/mine',
            componentKey: null,
            permission: null,
            icon: 'mdi:mine',
            sortOrder: 600,
            visible: true,
            children: [
              {
                id: 'mine-location',
                parentId: 'mine-root',
                name: '实时定位',
                type: 'MENU',
                path: '/mine/location',
                componentKey: 'mine-location-monitor',
                permission: 'mine:location:view',
                icon: 'mdi:map-marker-radius',
                sortOrder: 660,
                visible: true,
                children: []
              }
            ]
          }
        ]
      }
    })
  );
  await page.route('**/api/v1/mine/context', route =>
    route.fulfill({
      json: {
        organizations: [
          {
            organization: {
              id: 'organization-1', organizationCode: 'NORTH', name: '华北矿业', status: 'ACTIVE',
              archivedAt: null, createdAt: occurredAt, updatedAt: occurredAt, version: 0
            },
            organizationWide: false,
            sites: [
              {
                id: 'site-1', organizationId: 'organization-1', siteCode: 'PIT-A', name: 'A矿',
                timezone: 'Asia/Shanghai', description: '主采区', status: 'ACTIVE', archivedAt: null,
                createdAt: occurredAt, updatedAt: occurredAt, version: 0
              }
            ]
          }
        ]
      }
    })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/vehicles\?.*$/, route =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'vehicle-1', organizationId: 'organization-1', siteId: 'site-1', vehicleCode: 'TRUCK-01',
            name: '1号矿卡', vehicleType: 'HAUL_TRUCK', mineNumber: 'M-001', licensePlate: null,
            brand: null, model: null, ratedLoadTons: 90, status: 'ACTIVE', archivedAt: null,
            createdAt: occurredAt, updatedAt: occurredAt, version: 0
          }
        ],
        page: 1,
        size: 100,
        total: 1
      }
    })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/people\?.*$/, route =>
    route.fulfill({ json: { items: [], page: 1, size: 100, total: 0 } })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/locations\/current\?.*$/, route =>
    route.fulfill({
      json: {
        siteId: 'site-1',
        watermark: 10,
        source: 'REDIS',
        truncated: false,
        items: [location({ occurredAt, ingestSequence: 10 })]
      }
    })
  );
  await page.route('**/api/v1/mine/sites/site-1/spatial-regions/current', route => {
    spatialRegionRequests += 1;
    return route.fulfill({
      json: {
        siteId: 'site-1',
        evaluatedAt: occurredAt,
        nextChangeAt: null,
        items: [
          {
            zoneType: 'GEOFENCE', zoneId: 'zone-1', zoneVersionId: 'zone-version-1',
            coordinateSystemId: 'coordinate-1', name: '北区禁入围栏',
            contentHash: `sha256:${'a'.repeat(64)}`, validFrom: '2020-01-01T00:00:00Z', validTo: null,
            shape: 'POLYGON', x: null, y: null, radiusMeters: null,
            vertices: [
              { sequenceNumber: 1, x: 100, y: 60 },
              { sequenceNumber: 2, x: 160, y: 60 },
              { sequenceNumber: 3, x: 160, y: 120 }
            ]
          }
        ]
      }
    });
  });
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/spatial-events\?.*$/, route => {
    spatialSnapshotRequests += 1;
    return route.fulfill({
      json: {
        siteId: 'site-1',
        from: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
        to: occurredAt,
        watermark: 40,
        nextCursor: null,
        items: [spatialEvent('snapshot-event', 'ENTER', occurredAt, { eventSequence: 40 })]
      }
    });
  });
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/spatial-events\/[^/?]+$/, route => {
    const eventId = route.request().url().split('/').at(-1)!;
    return route.fulfill({
      json: {
        ...spatialEvent(eventId, eventId.includes('exit') ? 'EXIT' : 'ENTER', occurredAt, { eventSequence: 43 }),
        anchorLocationEventId: 'location-event-1',
        createdAt: occurredAt,
        evidence: evidence()
      }
    });
  });
  await page.routeWebSocket('**/api/v1/realtime', socket => {
    let sequence = 0;
    let gapScheduled = false;
    socket.onMessage(message => {
      const request = JSON.parse(String(message));
      if (request.action === 'AUTH') {
        socket.send(JSON.stringify({
          type: 'ACK', action: 'AUTH', requestId: request.requestId, sequence: ++sequence
        }));
        return;
      }
      const topic = request.topics?.[0]?.type;
      if (request.action === 'SUBSCRIBE' && topic === 'MINE_SITE_LOCATION') {
        socket.send(JSON.stringify({
          type: 'ACK', action: 'SUBSCRIBE', requestId: request.requestId, sequence: ++sequence
        }));
        socket.send(JSON.stringify({
          type: 'SNAPSHOT', topic, siteId: 'site-1', watermark: 10, source: 'REDIS', truncated: false,
          items: [location({ occurredAt, ingestSequence: 10 })], sequence: ++sequence
        }));
      }
      if (request.action === 'SUBSCRIBE' && topic === 'MINE_SITE_SPATIAL_EVENT') {
        socket.send(JSON.stringify({
          type: 'ACK', action: 'SUBSCRIBE', requestId: request.requestId, sequence: ++sequence
        }));
        socket.send(JSON.stringify({
          type: 'DATA', topic, siteId: 'site-1',
          item: realtimeSpatialEvent('live-enter', 'ENTER', occurredAt), sequence: ++sequence
        }));
        if (!gapScheduled) {
          gapScheduled = true;
          setTimeout(() => {
            sequence += 2;
            socket.send(JSON.stringify({
              type: 'DATA', topic, siteId: 'site-1',
              item: realtimeSpatialEvent('live-dwell', 'DWELL', occurredAt), sequence
            }));
            socket.send(JSON.stringify({
              type: 'DATA', topic, siteId: 'site-1',
              item: realtimeSpatialEvent('live-exit', 'EXIT', occurredAt), sequence: ++sequence
            }));
          }, 100);
        }
      }
      if (request.action === 'RESYNC') {
        socket.send(JSON.stringify({
          type: 'ACK', action: 'RESYNC', requestId: request.requestId, sequence: ++sequence
        }));
      }
    });
  });

  await page.goto('/mine/location');
  await expect(page.getByText('矿区实时定位', { exact: true })).toBeVisible();
  await expect(page.getByText('已发布区域', { exact: true })).toBeVisible();
  await expect(page.getByRole('img', { name: '矿区二维定位画布' })).toBeVisible();
  await expect(page.getByText('车辆 · 1号矿卡 · TRUCK-01 离开 北区禁入围栏')).toBeVisible();
  await expect(page.getByText('进入', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('停留', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('离开', { exact: true }).first()).toBeVisible();
  expect(spatialRegionRequests).toBe(1);
  await expect.poll(() => spatialSnapshotRequests).toBeGreaterThan(1);

  await page.getByRole('button', { name: '证据' }).first().click();
  const evidenceDialog = page.getByRole('dialog');
  await expect(evidenceDialog.getByText('空间事件判定证据', { exact: true })).toBeVisible();
  await expect(evidenceDialog.getByText('(120, 88)', { exact: true })).toBeVisible();
  await expect(evidenceDialog.getByText(`sha256:${'a'.repeat(64)}`, { exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(evidenceDialog).toBeHidden();

  await page.getByRole('checkbox', { name: '已发布区域' }).uncheck();
  await expect(page.getByRole('checkbox', { name: '已发布区域' })).not.toBeChecked();
  await page.getByRole('checkbox', { name: '实时事件' }).uncheck();
  await expect(page.getByRole('checkbox', { name: '实时事件' })).not.toBeChecked();
});

function location(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'location-event-1', organizationId: 'organization-1', siteId: 'site-1',
    sourceDeviceId: 'device-1', tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1',
    vehicleId: 'vehicle-1', personId: null, ingestSequence: 10, occurredAt: '2026-07-19T00:00:00Z',
    receivedAt: '2026-07-19T00:00:01Z', localX: 120, localY: 88, localZ: null,
    speedMetersPerSecond: 4.5, headingDegrees: 90, accuracyMeters: 0.5,
    qualityStatus: 'GOOD', qualityReason: null, ...overrides
  };
}

function spatialEvent(eventId: string, eventType: 'ENTER' | 'EXIT' | 'DWELL', occurredAt: string, overrides = {}) {
  const state = eventType === 'ENTER'
    ? { previousState: 'OUTSIDE', currentState: 'INSIDE' }
    : eventType === 'EXIT'
      ? { previousState: 'INSIDE', currentState: 'OUTSIDE' }
      : { previousState: 'INSIDE', currentState: 'INSIDE' };
  return {
    eventId, eventSequence: 41, organizationId: 'organization-1', siteId: 'site-1', zoneType: 'GEOFENCE',
    zoneId: 'zone-1', zoneVersionId: 'zone-version-1', tagId: 'tag-1', vehicleId: 'vehicle-1',
    personId: null, eventType, ...state, confirmationPoints: 3, ingestSequence: 11,
    runtimeRevision: 5, occurredAt, ...overrides
  };
}

function realtimeSpatialEvent(eventId: string, eventType: 'ENTER' | 'EXIT' | 'DWELL', occurredAt: string) {
  const { eventSequence: _eventSequence, ...event } = spatialEvent(eventId, eventType, occurredAt);
  return { ...event, anchorLocationEventId: 'location-event-1', evidence: evidence() };
}

function evidence() {
  return {
    localX: 120, localY: 88, localZ: null, classification: 'INSIDE', qualityStatus: 'GOOD',
    qualityReason: null, zoneContentHash: `sha256:${'a'.repeat(64)}`
  };
}
