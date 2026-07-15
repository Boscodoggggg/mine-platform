import { expect, test } from '@playwright/test';

test('模拟设备实时上报后数值卡应更新', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });

  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: { id: 'user-1', username: 'mine-admin', displayName: '矿区管理员', passwordChangeRequired: false }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['SUPER_ADMIN'],
        permissions: ['device:telemetry:view'],
        menus: [
          {
            id: 'device-root',
            parentId: null,
            name: '设备管理',
            type: 'DIRECTORY',
            path: '/device',
            componentKey: null,
            permission: null,
            icon: 'mdi:factory',
            sortOrder: 100,
            visible: true,
            children: [
              {
                id: 'device-monitor',
                parentId: 'device-root',
                name: '设备监控',
                type: 'MENU',
                path: '/device/monitor',
                componentKey: 'device-monitor',
                permission: 'device:telemetry:view',
                icon: 'mdi:monitor-dashboard',
                sortOrder: 30,
                visible: true,
                children: []
              }
            ]
          }
        ]
      }
    })
  );
  await page.route(/\/api\/v1\/devices\?.*$/, route =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'device-1',
            productId: 'product-1',
            deviceCode: 'simulator-001',
            name: '模拟运输车',
            status: 'ENABLED',
            archivedAt: null,
            createdAt: '2026-07-15T00:00:00Z',
            updatedAt: '2026-07-15T00:00:00Z',
            version: 0
          }
        ],
        page: 1,
        size: 100,
        total: 1
      }
    })
  );
  await page.route(/\/api\/v1\/devices\/device-1\/latest\?.*$/, route =>
    route.fulfill({
      json: {
        deviceId: 'device-1',
        items: [
          {
            propertyId: 'engine.rpm',
            value: 1280,
            occurredAt: '2026-07-15T00:00:00Z',
            receivedAt: '2026-07-15T00:00:01Z',
            quality: 'GOOD',
            eventId: 'event-1',
            source: 'REDIS'
          }
        ],
        missingPropertyIds: ['coolant.temperature', 'vehicle.speed', 'fuel.level']
      }
    })
  );

  await page.routeWebSocket('**/api/v1/realtime', socket => {
    let sequence = 0;
    socket.onMessage(message => {
      const request = JSON.parse(String(message));
      if (request.action === 'AUTH') {
        socket.send(
          JSON.stringify({ type: 'ACK', action: 'AUTH', requestId: request.requestId, sequence: ++sequence })
        );
      }
      if (request.action === 'SUBSCRIBE') {
        socket.send(
          JSON.stringify({
            type: 'DATA',
            deviceId: 'device-1',
            values: { 'engine.rpm': 1450 },
            quality: 'GOOD',
            receivedAt: '2026-07-15T00:00:02Z',
            eventId: 'event-2',
            sequence: ++sequence
          })
        );
      }
    });
  });

  await page.goto('/device/monitor');
  await expect(page.getByText('设备实时监控')).toBeVisible();
  await page.getByRole('textbox').click();
  await page.getByText('模拟运输车 (simulator-001)').click();

  await expect(page.getByText('1450')).toBeVisible();
  await expect(page.getByText('实时订阅中')).toBeVisible();
});
