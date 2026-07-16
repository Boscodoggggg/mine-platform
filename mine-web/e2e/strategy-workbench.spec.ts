import { expect, test } from '@playwright/test';

test('策略管理员应查看最终值并通过幂等发布形成新的不可变记录', async ({ page }) => {
  const backend = await mockStrategyWorkbench(page, false);

  await page.goto('/device/strategies');
  await expect(page.getByText('策略工作台', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('北区运输组', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('北区运输车 01', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('设备组覆盖', { exact: true }).last()).toBeVisible();
  await expect(page.getByText('180', { exact: true }).last()).toBeVisible();

  await page.locator('[data-field="publish-reason"] textarea').fill('雨季延长离线判定窗口');
  await page.locator('[data-action="publish-strategy"]').click();

  await expect(page.getByText('发布序号 #9', { exact: true })).toBeVisible();
  expect(backend.publishAttempts).toBe(1);
  expect(backend.publishIdempotencyKey).toMatch(/\S+/);
  expect(backend.publishBody).toMatchObject({
    strategyVersionId: 'version-3',
    scopeType: 'PRODUCT',
    scopeId: 'product-1',
    reason: '雨季延长离线判定窗口'
  });
});

test('同优先级策略冲突应保留在当前工作台并展示可追踪证据', async ({ page }) => {
  const backend = await mockStrategyWorkbench(page, true);

  await page.goto('/device/strategies');
  await page.locator('[data-field="publish-reason"] textarea').fill('冲突发布必须被阻止');
  await page.locator('[data-action="publish-strategy"]').click();

  await expect(page.getByText('策略存在冲突', { exact: true })).toBeVisible();
  await expect(page.getByText(/STRATEGY-409-001/)).toBeVisible();
  await expect(page.getByText(/trace-strategy-conflict/)).toBeVisible();
  await expect(page.getByText(/group-a/)).toBeVisible();
  await expect(page.getByText(/group-b/)).toBeVisible();
  expect(backend.publishAttempts).toBe(1);
  expect(backend.releases).toHaveLength(1);
});

async function mockStrategyWorkbench(page: import('@playwright/test').Page, conflictPublish: boolean) {
  const state = {
    releases: [strategyRelease('release-8', 8)],
    publishAttempts: 0,
    publishIdempotencyKey: '',
    publishBody: null as Record<string, unknown> | null
  };

  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: { id: 'user-1', username: 'strategy-admin', displayName: '策略管理员', passwordChangeRequired: false }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['STRATEGY_ADMIN'],
        permissions: [
          'device:product:view',
          'device:device:view',
          'device:group:view',
          'device:group:edit',
          'device:strategy:view',
          'device:strategy:edit',
          'device:strategy:publish'
        ],
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
                id: 'strategy-menu',
                parentId: 'device-root',
                name: '策略工作台',
                type: 'MENU',
                path: '/device/strategies',
                componentKey: 'device-strategy-workbench',
                permission: 'device:strategy:view',
                icon: 'mdi:tune-variant',
                sortOrder: 35,
                visible: true,
                i18nKey: 'mine.menu.strategies',
                children: []
              }
            ]
          }
        ]
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
    route.fulfill({
      json: { items: [device()], page: 1, size: 200, total: 1 }
    })
  );
  await page.route(/\/api\/v1\/device-groups\?.*$/, route =>
    route.fulfill({
      json: { items: [deviceGroup()], page: 1, size: 100, total: 1 }
    })
  );
  await page.route('**/api/v1/device-groups/group-1/members', route =>
    route.fulfill({ json: [{ ...device(), effectiveFrom: '2026-07-16T01:00:00Z' }] })
  );
  await page.route(/\/api\/v1\/strategy-versions\?.*$/, route => route.fulfill({ json: [strategyDraft()] }));
  await page.route('**/api/v1/strategy-releases**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname.endsWith('/strategy-releases')) {
      return route.fulfill({ json: state.releases });
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/strategy-releases')) {
      state.publishAttempts += 1;
      state.publishIdempotencyKey = request.headers()['idempotency-key'] || '';
      state.publishBody = request.postDataJSON() as Record<string, unknown>;
      if (conflictPublish) {
        return route.fulfill({
          status: 409,
          json: {
            code: 'STRATEGY-409-001',
            message: '同优先级设备组配置冲突',
            traceId: 'trace-strategy-conflict',
            details: { field: 'offlineThresholdSeconds', groupIds: ['group-a', 'group-b'] }
          }
        });
      }
      const created = strategyRelease('release-9', 9);
      state.releases = [created, ...state.releases];
      return route.fulfill({ status: 201, json: created });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/devices/device-1/effective-strategies/ONLINE_STATUS', route =>
    route.fulfill({
      json: {
        deviceId: 'device-1',
        productId: 'product-1',
        strategyType: 'ONLINE_STATUS',
        valid: true,
        values: { offlineThresholdSeconds: 180 },
        sources: {
          offlineThresholdSeconds: {
            scopeType: 'GROUP',
            scopeId: 'group-1',
            releaseId: 'release-8',
            groupPriority: 900
          }
        },
        conflicts: [],
        contentHash: `sha256:${'a'.repeat(64)}`
      }
    })
  );

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

function deviceGroup() {
  return {
    id: 'group-1',
    productId: 'product-1',
    groupKey: 'north-haul',
    name: '北区运输组',
    priority: 900,
    status: 'ACTIVE',
    description: '北区运输设备',
    archivedAt: null,
    createdAt: '2026-07-16T00:00:00Z',
    updatedAt: '2026-07-16T00:00:00Z',
    version: 2,
    memberCount: 1
  };
}

function strategyDraft() {
  return {
    id: 'version-3',
    productId: 'product-1',
    strategyType: 'ONLINE_STATUS',
    versionNumber: 3,
    status: 'DRAFT',
    values: { offlineThresholdSeconds: 180 },
    contentHash: null,
    description: '雨季运输基线',
    publishedAt: null,
    createdAt: '2026-07-16T00:00:00Z',
    updatedAt: '2026-07-16T00:00:00Z',
    version: 1
  };
}

function strategyRelease(id: string, sequence: number) {
  return {
    id,
    productId: 'product-1',
    strategyVersionId: 'version-3',
    strategyType: 'ONLINE_STATUS',
    releaseSequence: sequence,
    scopeType: 'GROUP',
    scopeId: 'group-1',
    groupPriority: 900,
    action: 'PUBLISH',
    rollbackOfReleaseId: null,
    contentHash: `sha256:${String(sequence).repeat(64)}`
  };
}
