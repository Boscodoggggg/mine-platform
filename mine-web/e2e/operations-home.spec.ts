import { expect, test } from '@playwright/test';

test('调度首页与系统运行监控应使用各自的信息层级', async ({ page }) => {
  await mockOperations(page);

  await page.goto('/home');
  await expect(page.getByText('矿区运行态势', { exact: true })).toBeVisible();
  await expect(page.getByText('九号矿卡')).toBeVisible();
  await expect(page.getByText('数据中断', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('Phase 0')).toHaveCount(0);

  await page.getByRole('menuitem', { name: '系统管理' }).click();
  await page.getByRole('menuitem', { name: '运行监控' }).click();

  await expect(page).toHaveURL(/\/system\/operations$/);
  await expect(page.getByText('平台服务', { exact: true })).toBeVisible();
  await expect(page.getByText('消息中枢', { exact: true })).toBeVisible();
  await expect(page.getByText('存储与缓存', { exact: true })).toBeVisible();
  await expect(page.getByText('RabbitMQ', { exact: true })).toBeVisible();
});

test('没有运行监控权限时不应展示管理员菜单', async ({ page }) => {
  await mockOperations(page, false);

  await page.goto('/home');
  await expect(page.getByText('矿区运行态势', { exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '运行监控' })).toHaveCount(0);
});

async function mockOperations(page: import('@playwright/test').Page, includeOperations = true) {
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
        roles: ['MINE_ADMIN'],
        permissions: includeOperations ? ['system:operations:view'] : [],
        menus: [
          {
            id: 'system-root',
            parentId: null,
            name: '系统管理',
            type: 'DIRECTORY',
            path: '/system',
            componentKey: null,
            permission: null,
            icon: 'mdi:cog-outline',
            sortOrder: 900,
            visible: true,
            children: includeOperations
              ? [
              {
                id: 'system-operations',
                parentId: 'system-root',
                name: '运行监控',
                type: 'MENU',
                path: '/system/operations',
                componentKey: 'system-operations-monitor',
                permission: 'system:operations:view',
                icon: 'mdi:server-network-outline',
                sortOrder: 30,
                visible: true,
                children: []
              }
                ]
              : []
          }
        ]
      }
    })
  );
  await page.route('**/api/v1/dashboard/operations', route =>
    route.fulfill({
      json: {
        generatedAt: '2026-07-16T02:30:00Z',
        dataStatus: 'AVAILABLE',
        freshnessSeconds: 300,
        devices: { enabled: 12, reporting: 8, stale: 3, neverReported: 1 },
        telemetry: {
          todayMessages: 12500,
          latestReceivedAt: '2026-07-16T02:29:58Z',
          hourly: [{ hour: '2026-07-16T02:00:00Z', count: 730 }]
        },
        attentionDevices: [
          {
            deviceId: 'device-9',
            deviceCode: 'truck-009',
            name: '九号矿卡',
            state: 'STALE',
            lastSeenAt: '2026-07-16T02:10:00Z'
          }
        ]
      }
    })
  );
  await page.route('**/api/v1/system/operations', route =>
    route.fulfill({
      json: {
        status: 'DEGRADED',
        checkedAt: '2026-07-16T03:00:00Z',
        items: [
          { key: 'mine-server', category: 'SERVICE', status: 'UP', latencyMillis: 3, summary: 'serviceUp' },
          { key: 'rabbitmq', category: 'MESSAGE', status: 'DOWN', latencyMillis: 50, summary: 'healthFailed' },
          { key: 'timescale', category: 'STORAGE', status: 'UP', latencyMillis: 8, summary: 'connectionUp' }
        ]
      }
    })
  );
}
