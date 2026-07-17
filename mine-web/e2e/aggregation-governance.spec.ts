import { expect, test } from '@playwright/test';

test('聚合治理人员应从动态菜单查看运行事实并登记有界重算', async ({ page }) => {
  const backend = await mockAggregationGovernance(page);
  let confirmation = '';
  page.on('dialog', async dialog => {
    confirmation = dialog.message();
    await dialog.accept();
  });

  await page.goto('/home');
  await page.getByRole('menuitem', { name: '数据治理' }).click();
  await page.getByRole('menuitem', { name: '数值聚合' }).click();

  await expect(page).toHaveURL(/\/governance\/aggregation$/);
  await expect(page.locator('[data-page-title="aggregation-governance"]')).toHaveText('数值聚合');
  await expect(page.getByText('部分完成', { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/生成 42 个桶/)).toBeVisible();
  await expect(page.getByText('待执行', { exact: true })).toBeVisible();

  await page.locator('[data-field="aggregation-property"] input').fill('engine.rpm');
  const rangeInputs = page.locator('[data-field="aggregation-range"] input');
  await rangeInputs.nth(0).fill('2026-07-01 00:00:00');
  await rangeInputs.nth(1).fill('2026-07-02 00:00:00');
  await rangeInputs.nth(1).press('Tab');
  await page.locator('[data-field="aggregation-reason"] textarea').fill('补算弱网恢复前历史');
  await page.locator('[data-action="submit-recomputation"]').click();

  await expect.poll(() => backend.recomputeAttempts).toBe(1);
  expect(confirmation).toContain('不会原地覆盖');
  expect(backend.idempotencyKey).toMatch(/^aggregation-recompute-/);
  expect(backend.recomputeBody).toMatchObject({
    deviceId: 'device-1',
    propertyId: 'engine.rpm',
    bucket: 'PT5M',
    from: '2026-06-30T16:00:00.000Z',
    to: '2026-07-01T16:00:00.000Z',
    reason: '补算弱网恢复前历史'
  });
});

async function mockAggregationGovernance(page: import('@playwright/test').Page) {
  const state = {
    recomputeAttempts: 0,
    recomputeBody: null as Record<string, unknown> | null,
    idempotencyKey: ''
  };
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: {
        id: 'user-1',
        username: 'aggregation-admin',
        displayName: '聚合治理管理员',
        passwordChangeRequired: false
      }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['AGGREGATION_ADMIN'],
        permissions: ['governance:aggregation:view', 'governance:aggregation:execute'],
        menus: [
          {
            id: 'governance-root',
            parentId: null,
            name: '数据治理',
            type: 'DIRECTORY',
            path: '/governance',
            componentKey: null,
            permission: null,
            icon: 'mdi:database-cog-outline',
            sortOrder: 175,
            visible: true,
            i18nKey: 'mine.menu.governance',
            children: [
              {
                id: 'aggregation-menu',
                parentId: 'governance-root',
                name: '数值聚合',
                type: 'MENU',
                path: '/governance/aggregation',
                componentKey: 'aggregation-governance',
                permission: 'governance:aggregation:view',
                icon: 'mdi:chart-timeline-variant-shimmer',
                sortOrder: 20,
                visible: true,
                i18nKey: 'mine.menu.aggregation',
                children: []
              }
            ]
          }
        ]
      }
    })
  );
  await page.route('**/api/v1/dashboard/operations', route =>
    route.fulfill({
      json: {
        generatedAt: '2026-07-17T02:30:00Z',
        dataStatus: 'AVAILABLE',
        freshnessSeconds: 60,
        devices: { enabled: 1, reporting: 1, stale: 0, neverReported: 0 },
        telemetry: { todayMessages: 128, latestReceivedAt: '2026-07-17T02:29:58Z', hourly: [] },
        attentionDevices: []
      }
    })
  );
  await page.route(/\/api\/v1\/products\?.*$/, route =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'product-1',
            productKey: 'haul-truck',
            name: '矿用运输车',
            deviceType: 'VEHICLE',
            status: 'ACTIVE',
            archivedAt: null,
            createdAt: '2026-07-16T00:00:00Z',
            updatedAt: '2026-07-16T00:00:00Z',
            version: 0
          }
        ],
        page: 1,
        size: 100,
        total: 1
      }
    })
  );
  await page.route(/\/api\/v1\/devices\?.*$/, route =>
    route.fulfill({ json: { items: [device()], page: 1, size: 100, total: 1 } })
  );
  await page.route(/\/api\/v1\/aggregation-executions\?.*$/, route =>
    route.fulfill({
      json: [
        {
          executionId: '00000000-0000-0000-0000-000000000101',
          instanceId: 'telemetry-1',
          status: 'PARTIAL',
          policyCount: 3,
          ruleCount: 5,
          sourceWatermark: 100,
          bucketCount: 42,
          rollupWatermark: 88,
          lateRowsIgnored: 2,
          startedAt: '2026-07-17T02:00:00Z',
          completedAt: '2026-07-17T02:00:03Z',
          lastError: null
        }
      ]
    })
  );
  await page.route(/\/api\/v1\/aggregation-recomputations(?:\?.*)?$/, async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [recomputation()] });
    state.recomputeAttempts += 1;
    state.recomputeBody = route.request().postDataJSON() as Record<string, unknown>;
    state.idempotencyKey = route.request().headers()['idempotency-key'] || '';
    return route.fulfill({ status: 201, json: recomputation() });
  });
  return state;
}

function device() {
  return {
    id: 'device-1',
    productId: 'product-1',
    deviceCode: 'TRUCK-001',
    name: '北区运输车 01',
    status: 'ENABLED',
    archivedAt: null,
    createdAt: '2026-07-16T00:00:00Z',
    updatedAt: '2026-07-16T00:00:00Z',
    version: 0
  };
}

function recomputation() {
  return {
    requestId: '00000000-0000-0000-0000-000000000201',
    deviceId: 'device-1',
    propertyId: 'engine.rpm',
    bucket: 'PT5M',
    from: '2026-07-01T00:00:00Z',
    to: '2026-07-02T00:00:00Z',
    reason: '补算弱网恢复前历史',
    idempotencyKey: 'request-1',
    status: 'PENDING',
    requestedBy: '00000000-0000-0000-0000-000000000001',
    createdAt: '2026-07-17T03:00:00Z',
    completedAt: null,
    executionId: null,
    lastError: null
  };
}
