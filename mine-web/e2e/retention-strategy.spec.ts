import { expect, test } from '@playwright/test';

test('策略管理员应先预览删除影响再确认发布保留策略', async ({ page }) => {
  const backend = await mockRetentionWorkbench(page);
  let confirmation = '';
  page.on('dialog', async dialog => {
    confirmation = dialog.message();
    await dialog.accept();
  });

  await page.goto('/home');
  await page.getByRole('menuitem', { name: '数据治理' }).click();
  await page.getByRole('menuitem', { name: '数据保留' }).click();

  await expect(page).toHaveURL(/\/governance\/retention$/);
  await expect(page.locator('[data-page-title="retention-governance"]')).toHaveText('数据保留');
  await expect(page.locator('[data-field="strategy-type"]')).toHaveCount(0);
  await expect(page.getByText('安全保留预设', { exact: true })).toBeVisible();
  await expect(page.getByText(/原始遥测 800 行/)).toBeVisible();
  await page.locator('[data-action="preview-retention"]').click();

  await expect(page.getByText('1,000,000+ 行候选数据', { exact: true })).toBeVisible();
  await expect(page.getByText('1,001 台设备', { exact: true })).toBeVisible();
  expect(backend.previewBody).toMatchObject({
    productId: 'product-1',
    scopeType: 'PRODUCT',
    scopeId: 'product-1',
    policy: {
      enabled: true,
      retentionClass: 'STANDARD',
      rawTelemetryDays: 90,
      qualityEvidenceDays: 180
    }
  });

  await page.locator('[data-field="publish-reason"] textarea').fill('按标准周期清理历史遥测');
  await page.locator('[data-action="publish-strategy"]').click();

  expect(confirmation).toContain('2026-07-17T02:30:00Z');
  expect(confirmation).toContain('不可恢复');
  expect(backend.publishBody).toMatchObject({
    strategyVersionId: 'retention-version-2',
    scopeType: 'PRODUCT',
    scopeId: 'product-1',
    reason: '按标准周期清理历史遥测'
  });
  expect(backend.idempotencyKey).toMatch(/\S+/);
});

async function mockRetentionWorkbench(page: import('@playwright/test').Page) {
  const state = {
    previewBody: null as Record<string, unknown> | null,
    publishBody: null as Record<string, unknown> | null,
    idempotencyKey: ''
  };
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: { id: 'user-1', username: 'retention-admin', displayName: '保留策略管理员', passwordChangeRequired: false }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['STRATEGY_ADMIN'],
        permissions: [
          'device:product:view',
          'device:device:view',
          'device:strategy:view',
          'device:strategy:edit',
          'device:strategy:publish',
          'governance:retention:view'
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
          },
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
                id: 'retention-menu',
                parentId: 'governance-root',
                name: '数据保留',
                type: 'MENU',
                path: '/governance/retention',
                componentKey: 'retention-governance',
                permission: 'governance:retention:view',
                icon: 'mdi:database-clock-outline',
                sortOrder: 10,
                visible: true,
                i18nKey: 'mine.menu.retention',
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
    route.fulfill({
      json: {
        items: [
          {
            id: 'device-1',
            productId: 'product-1',
            deviceCode: 'TRUCK-001',
            name: '北区运输车 01',
            status: 'ENABLED',
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
  await page.route(/\/api\/v1\/strategy-versions\?.*$/, route => {
    const strategyType = new URL(route.request().url()).searchParams.get('strategyType');
    return route.fulfill({ json: [strategyType === 'RETENTION' ? retentionDraft() : onlineDraft()] });
  });
  await page.route('**/api/v1/strategy-versions/retention-version-2', route =>
    route.fulfill({ json: retentionDraft() })
  );
  await page.route(/\/api\/v1\/strategy-releases(?:\?.*)?$/, async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [] });
    state.publishBody = route.request().postDataJSON() as Record<string, unknown>;
    state.idempotencyKey = route.request().headers()['idempotency-key'] || '';
    return route.fulfill({
      status: 201,
      json: {
        id: 'retention-release-3',
        productId: 'product-1',
        strategyVersionId: 'retention-version-2',
        strategyType: 'RETENTION',
        releaseSequence: 3,
        scopeType: 'PRODUCT',
        scopeId: 'product-1',
        groupPriority: null,
        action: 'PUBLISH',
        rollbackOfReleaseId: null,
        contentHash: `sha256:${'f'.repeat(64)}`
      }
    });
  });
  await page.route(/\/api\/v1\/devices\/device-1\/effective-strategies\/.+$/, route => {
    const retention = route.request().url().endsWith('/RETENTION');
    return route.fulfill({
      json: {
        deviceId: 'device-1',
        productId: 'product-1',
        strategyType: retention ? 'RETENTION' : 'ONLINE_STATUS',
        valid: true,
        values: retention ? retentionDraft().values : { offlineThresholdSeconds: 180 },
        sources: retention
          ? { policy: { scopeType: 'PRODUCT', scopeId: 'product-1', releaseId: 'retention-release-2', groupPriority: null } }
          : { offlineThresholdSeconds: { scopeType: 'PRODUCT', scopeId: 'product-1', releaseId: 'online-release-1', groupPriority: null } },
        conflicts: [],
        contentHash: `sha256:${'a'.repeat(64)}`
      }
    });
  });
  await page.route('**/api/v1/retention-previews', route => {
    state.previewBody = route.request().postDataJSON() as Record<string, unknown>;
    return route.fulfill({
      json: {
        observedAt: '2026-07-17T02:30:00Z',
        affectedDeviceCount: 1001,
        policy: retentionDraft().values.policy,
        rawTelemetry: { eligibleRows: 1_000_000, earliestEligibleAt: '2025-01-01T00:00:00Z', atLeast: true },
        qualityEvidence: { eligibleRows: 420, earliestEligibleAt: '2025-02-01T00:00:00Z', atLeast: false }
      }
    });
  });
  await page.route(/\/api\/v1\/retention-executions\?.*$/, route =>
    route.fulfill({
      json: [
        {
          executionId: '00000000-0000-0000-0000-000000000101',
          instanceId: 'telemetry-1',
          status: 'SUCCEEDED',
          policyCount: 3,
          rawRowsDeleted: 800,
          qualityRowsDeleted: 120,
          batchCount: 4,
          startedAt: '2026-07-17T02:00:00Z',
          completedAt: '2026-07-17T02:00:03Z',
          lastError: null
        }
      ]
    })
  );
  return state;
}

function retentionDraft() {
  return {
    id: 'retention-version-2',
    productId: 'product-1',
    strategyType: 'RETENTION',
    versionNumber: 2,
    status: 'DRAFT',
    values: {
      policy: {
        enabled: true,
        retentionClass: 'STANDARD',
        rawTelemetryDays: 90,
        qualityEvidenceDays: 180
      }
    },
    contentHash: null,
    description: '运输车标准保留基线',
    publishedAt: null,
    createdAt: '2026-07-16T00:00:00Z',
    updatedAt: '2026-07-16T00:00:00Z',
    version: 1
  };
}

function onlineDraft() {
  return {
    ...retentionDraft(),
    id: 'online-version-1',
    strategyType: 'ONLINE_STATUS',
    versionNumber: 1,
    values: { offlineThresholdSeconds: 180 },
    description: '在线状态基线'
  };
}
