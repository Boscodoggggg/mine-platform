import { expect, test } from '@playwright/test';

test('运行作业收到完成趟次后应实时刷新列表和运营看板', async ({ page }) => {
  const now = '2026-07-20T05:00:00Z';
  let completed = false;
  let summaryRequests = 0;
  let workOrderRequests = 0;
  const realtime = { pushCompletion: null as (() => void) | null };
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
        permissions: ['mine:operation:view', 'mine:work-order:dispatch'],
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
                id: 'mine-operation',
                parentId: 'mine-root',
                name: '生产运营',
                type: 'MENU',
                path: '/mine/operations',
                componentKey: 'mine-production-operations',
                permission: 'mine:operation:view',
                icon: 'mdi:chart-timeline-variant-shimmer',
                sortOrder: 680,
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
              id: 'organization-1',
              organizationCode: 'NORTH',
              name: '华北矿业',
              status: 'ACTIVE',
              archivedAt: null,
              createdAt: now,
              updatedAt: now,
              version: 0
            },
            organizationWide: false,
            sites: [
              {
                id: 'site-1',
                organizationId: 'organization-1',
                siteCode: 'PIT-A',
                name: 'A矿',
                timezone: 'Asia/Shanghai',
                description: '主采区',
                status: 'ACTIVE',
                archivedAt: null,
                createdAt: now,
                updatedAt: now,
                version: 0
              }
            ]
          }
        ]
      }
    })
  );
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/operation-summary\?.*$/, route => {
    summaryRequests += 1;
    return route.fulfill({ json: operationSummary(completed) });
  });
  await page.route(/\/api\/v1\/mine\/sites\/site-1\/work-orders\?.*$/, route => {
    workOrderRequests += 1;
    return route.fulfill({ json: [runningWorkOrder(now)] });
  });
  await page.routeWebSocket('**/api/v1/realtime', socket => {
    let sequence = 0;
    socket.onMessage(message => {
      const request = JSON.parse(String(message));
      if (request.action === 'AUTH') {
        socket.send(JSON.stringify({
          type: 'ACK',
          action: 'AUTH',
          requestId: request.requestId,
          sequence: ++sequence
        }));
        return;
      }
      if (request.action === 'SUBSCRIBE' && request.topics?.[0]?.type === 'MINE_SITE_OPERATION') {
        socket.send(JSON.stringify({
          type: 'ACK',
          action: 'SUBSCRIBE',
          requestId: request.requestId,
          sequence: ++sequence
        }));
        realtime.pushCompletion = () => {
          completed = true;
          socket.send(JSON.stringify({
            type: 'DATA',
            topic: 'MINE_SITE_OPERATION',
            siteId: 'site-1',
            businessSequence: 17,
            item: {
              eventId: 'operation-event-17',
              organizationId: 'organization-1',
              siteId: 'site-1',
              aggregateType: 'OPERATION_RECORD',
              aggregateId: 'record-1',
              updateType: 'RECORD_COMPLETED',
              businessSequence: 17,
              occurredAt: now,
              summary: { operationRecordId: 'record-1', actualTons: 90 }
            },
            sequence: ++sequence
          }));
        };
      }
    });
  });

  await page.goto('/mine/operations');
  await expect(page.getByText('生产运营', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('实时增量已连接', { exact: true })).toBeVisible();
  expect(summaryRequests).toBeGreaterThanOrEqual(2);

  await page.getByText('作业单', { exact: true }).click();
  await expect(page.getByText('北区运输一班', { exact: true })).toBeVisible();
  await expect(page.getByText('运行中', { exact: true })).toBeVisible();
  expect(realtime.pushCompletion).not.toBeNull();
  realtime.pushCompletion?.();
  await expect.poll(() => workOrderRequests).toBeGreaterThan(1);

  await page.getByText('运营看板', { exact: true }).click();
  await expect(page.getByText('90 吨', { exact: true })).toBeVisible();
  await expect(page.getByText('已完成 1 / 计划 5 趟', { exact: true })).toBeVisible();
});

function operationSummary(completed: boolean) {
  return {
    siteId: 'site-1',
    fromDate: '2026-07-20',
    toDate: '2026-07-20',
    workOrderCount: 1,
    plannedCycles: 5,
    effectiveCompletedCycles: completed ? 1 : 0,
    anomalyCycles: 0,
    completionRatePercent: completed ? 20 : 0,
    plannedTons: 450,
    actualTons: completed ? 90 : null,
    knownTonsCycles: completed ? 1 : 0,
    unknownTonsCycles: 0,
    distanceMeters: completed ? 3200 : null,
    totalCycleSeconds: completed ? 1200 : 0,
    averageCycleSeconds: completed ? 1200 : null,
    averageLoadingSeconds: completed ? 300 : null,
    averageHaulingSeconds: completed ? 600 : null,
    averageUnloadingSeconds: completed ? 300 : null,
    breakdownsTruncated: false,
    workOrders: [],
    vehicles: [],
    drivers: []
  };
}

function runningWorkOrder(now: string) {
  return {
    id: 'work-order-1',
    organizationId: 'organization-1',
    siteId: 'site-1',
    shiftInstanceId: 'shift-1',
    orderNumber: 'HAUL-20260720-01',
    title: '北区运输一班',
    workType: 'HAULAGE',
    plannedStartAt: '2026-07-20T00:00:00Z',
    plannedEndAt: '2026-07-20T12:00:00Z',
    plannedCycles: 5,
    plannedTons: 450,
    priority: 50,
    routeVersionId: 'route-version-1',
    loadGeofenceVersionId: 'load-version-1',
    unloadGeofenceVersionId: 'unload-version-1',
    releaseSnapshot: {},
    status: 'RUNNING',
    releasedAt: now,
    startedAt: now,
    pausedAt: null,
    completedAt: null,
    cancelledAt: null,
    abortedAt: null,
    terminalReason: null,
    createdAt: now,
    updatedAt: now,
    version: 3,
    assignments: []
  };
}
