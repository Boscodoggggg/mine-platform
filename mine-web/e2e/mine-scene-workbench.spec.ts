import { readFile } from 'node:fs/promises';
import { dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const E2E_DIR = dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = resolve(E2E_DIR, '..');
const REPOSITORY_ROOT = resolve(WEB_ROOT, '..');
const CESIUM_BUILD_ROOT = resolve(WEB_ROOT, 'node_modules/cesium/Build/Cesium');
const DEMO_GLB = resolve(REPOSITORY_ROOT, 'deploy/demo/scene/demo-mine-grid.glb');

const ORGANIZATION_ID = '0198aef6-2e80-7000-8000-000000000001';
const SITE_A = '0198aef6-2e80-7000-8000-000000000010';
const SITE_B = '0198aef6-2e80-7000-8000-000000000011';
const ASSET_ID = '0198aef6-2e80-7000-8000-000000000030';
const NOW = '2026-07-20T08:00:00Z';

test('授权用户应在真实 WebGL 场景加载仓库内确定性 GLB', async ({ page }) => {
  test.setTimeout(90_000);
  await mockSceneShell(page, [site(SITE_A, 'PIT-A', 'A矿')], ['mine:scene:view']);
  await routeCesiumOfflineAssets(page);
  const demoBytes = await readFile(DEMO_GLB);
  let assetRequests = 0;
  await page.route(`**${assetEntryUrl(SITE_A)}`, route => {
    assetRequests += 1;
    return route.fulfill({
      status: 200,
      body: demoBytes,
      headers: {
        'accept-ranges': 'bytes',
        'content-length': String(demoBytes.length),
        'content-type': 'model/gltf-binary',
        etag: '"sha256:4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78"',
        'x-content-type-options': 'nosniff'
      }
    });
  });
  await page.route(`**/api/v1/mine/sites/${SITE_A}/scene-runtime`, route =>
    route.fulfill({ json: readyRuntime(SITE_A, [staticGlbLayer(SITE_A)]) })
  );

  await page.goto('/mine/scene');

  await expect(page.getByRole('menuitem', { name: '三维场景' })).toBeVisible();
  await expect(page.getByText('矿区三维场景', { exact: true })).toBeVisible();
  await expect(page.getByTestId('scene-3d-viewport')).toBeVisible();
  await expect(page.getByTestId('scene-3d-viewport').locator('canvas')).toBeVisible();
  await expect.poll(() => assetRequests).toBe(1);
  await page.waitForTimeout(500);
  await expect(page.getByTestId('scene-layer-degraded')).toHaveCount(0);
  await expect(page.getByTestId('canvas-viewport')).toHaveCount(0);
});

test('GLB 读取返回 500 时应只降级对应图层并保留三维场景', async ({ page }) => {
  test.setTimeout(90_000);
  await mockSceneShell(page, [site(SITE_A, 'PIT-A', 'A矿')], ['mine:scene:view']);
  await routeCesiumOfflineAssets(page);
  await page.route(`**${assetEntryUrl(SITE_A)}`, route =>
    route.fulfill({ status: 500, json: { code: 'SCENE-ASSET-READ-FAILED', message: '演示资源读取失败' } })
  );
  await page.route(`**/api/v1/mine/sites/${SITE_A}/scene-runtime`, route =>
    route.fulfill({ json: readyRuntime(SITE_A, [gridLayer(), staticGlbLayer(SITE_A, 2)]) })
  );

  await page.goto('/mine/scene');

  await expect(page.getByTestId('scene-layer-degraded')).toContainText('demo-mine-grid');
  await expect(page.getByTestId('scene-3d-viewport')).toBeVisible();
  await expect(page.getByTestId('canvas-viewport')).toHaveCount(0);
});

test('WebGL 不可用时二维视图仍应接收位置增量、业务事实并支持切换矿区', async ({ page }) => {
  await forceWebGlUnavailable(page);
  await mockSceneShell(
    page,
    [site(SITE_A, 'PIT-A', 'A矿'), site(SITE_B, 'PIT-B', 'B矿')],
    [
      'mine:scene:view',
      'mine:location:view',
      'mine:spatial:view',
      'mine:spatial:event:view',
      'mine:alarm:view',
      'mine:operation:view'
    ]
  );
  await mockDynamicSceneApis(page);
  let pushedLocations = 0;
  await page.routeWebSocket('**/api/v1/realtime', socket => {
    let connectionSequence = 0;
    socket.onMessage(message => {
      const request = JSON.parse(String(message));
      if (request.action === 'AUTH') {
        socket.send(JSON.stringify(ack(request, ++connectionSequence)));
        return;
      }
      if (request.action === 'SUBSCRIBE' || request.action === 'UNSUBSCRIBE' || request.action === 'RESYNC') {
        socket.send(JSON.stringify(ack(request, ++connectionSequence)));
      }
      const topic = request.topics?.[0];
      const selectedSiteId = topic?.ids?.[0];
      if (request.action === 'SUBSCRIBE' && topic?.type === 'MINE_SITE_LOCATION' && selectedSiteId) {
        socket.send(JSON.stringify({
          type: 'SNAPSHOT',
          topic: topic.type,
          siteId: selectedSiteId,
          watermark: 0,
          source: 'REDIS',
          truncated: false,
          items: [],
          sequence: ++connectionSequence
        }));
        setTimeout(() => {
          pushedLocations += 1;
          socket.send(JSON.stringify({
            type: 'DATA',
            topic: topic.type,
            siteId: selectedSiteId,
            item: location(selectedSiteId, { sourceSequence: 1, ingestSequence: undefined }),
            sequence: ++connectionSequence
          }));
        }, 150);
      }
    });
  });

  await page.goto('/mine/scene');
  await selectSite(page, 'A矿 · PIT-A');

  await expect(page.getByTestId('canvas-viewport')).toHaveAttribute('data-reason', 'WEBGL_UNAVAILABLE');
  await expect(page.getByTestId('scene-fallback-reason')).toHaveText(
    '浏览器或显卡未提供 WebGL，已自动切换二维视图'
  );
  const firstCanvasHost = page.getByRole('img', { name: '矿区二维定位画布' });
  await expect(firstCanvasHost).toBeVisible();
  await expect(firstCanvasHost.locator('canvas')).toBeVisible();
  await firstCanvasHost.evaluate(element => element.setAttribute('data-e2e-canvas-host', 'site-a'));
  await expect(page.getByTestId('scene-realtime-summary')).toContainText('1 个位置 · 1 个区域 · 1 个空间事件');
  await expect(page.getByTestId('scene-business-layers')).toContainText('1 条未关闭车载告警（1 条已定位）');
  await expect(page.getByTestId('scene-business-layers')).toContainText('A矿早班');
  await expect(page.getByTestId('scene-business-layers')).toContainText('A-HAUL-01 · A矿运输');
  await expect.poll(() => pushedLocations).toBeGreaterThanOrEqual(1);

  await selectSite(page, 'B矿 · PIT-B');

  await expect(page.getByTestId('canvas-viewport')).toHaveAttribute('data-reason', 'WEBGL_UNAVAILABLE');
  await expect(page.getByTestId('scene-realtime-summary')).toContainText('1 个位置 · 1 个区域 · 1 个空间事件');
  await expect(page.getByTestId('scene-business-layers')).toContainText('B矿早班');
  await expect(page.getByTestId('scene-business-layers')).toContainText('B-HAUL-01 · B矿运输');
  const replacementCanvasHost = page.getByRole('img', { name: '矿区二维定位画布' });
  await expect(replacementCanvasHost.locator('canvas')).toBeVisible();
  await expect(replacementCanvasHost).not.toHaveAttribute('data-e2e-canvas-host', 'site-a');
  await expect.poll(() => pushedLocations).toBeGreaterThanOrEqual(2);
});

test('非三维路由不应请求 Cesium 运行 chunk 或离线静态资源', async ({ page }) => {
  await mockSceneShell(page, [site(SITE_A, 'PIT-A', 'A矿')], ['mine:scene:view']);
  await page.route('**/api/v1/dashboard/operations', route =>
    route.fulfill({
      json: {
        generatedAt: NOW,
        dataStatus: 'AVAILABLE',
        freshnessSeconds: 5,
        devices: { enabled: 1, reporting: 1, stale: 0, neverReported: 0 },
        telemetry: { todayMessages: 1, latestReceivedAt: NOW, hourly: [] },
        attentionDevices: []
      }
    })
  );
  const cesiumRequests: string[] = [];
  page.on('request', request => {
    const url = request.url();
    if (/\/cesium\/|\/node_modules\/.+\/(?:cesium|@cesium)|\/src\/modules\/scene3d\//i.test(url)) {
      cesiumRequests.push(url);
    }
  });

  await page.goto('/home');

  await expect(page.getByText('矿区运行态势', { exact: true })).toBeVisible();
  await page.waitForTimeout(300);
  expect(cesiumRequests).toEqual([]);
});

async function mockSceneShell(page: Page, sites: ReturnType<typeof site>[], permissions: string[]) {
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: {
        id: 'user-1',
        username: 'scene-operator',
        displayName: '场景调度员',
        passwordChangeRequired: false
      }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['MINE_DISPATCHER'],
        permissions,
        menus: [{
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
          children: [{
            id: 'mine-scene',
            parentId: 'mine-root',
            name: '三维场景',
            type: 'MENU',
            path: '/mine/scene',
            componentKey: 'mine-scene-workbench',
            permission: 'mine:scene:view',
            icon: 'mdi:cube-scan',
            sortOrder: 690,
            visible: true,
            children: []
          }]
        }]
      }
    })
  );
  await page.route('**/api/v1/mine/context', route =>
    route.fulfill({
      json: {
        organizations: [{
          organization: {
            id: ORGANIZATION_ID,
            organizationCode: 'NORTH',
            name: '华北矿业',
            status: 'ACTIVE',
            archivedAt: null,
            createdAt: NOW,
            updatedAt: NOW,
            version: 0
          },
          organizationWide: false,
          sites
        }]
      }
    })
  );
}

async function mockDynamicSceneApis(page: Page) {
  for (const siteId of [SITE_A, SITE_B]) {
    const label = siteId === SITE_A ? 'A' : 'B';
    await page.route(`**/api/v1/mine/sites/${siteId}/scene-runtime`, route =>
      route.fulfill({ json: readyRuntime(siteId, dynamicLayers()) })
    );
    await page.route(new RegExp(`/api/v1/mine/sites/${siteId}/locations/current\\?.*$`), route =>
      route.fulfill({
        json: { siteId, watermark: 0, source: 'REDIS', truncated: false, items: [] }
      })
    );
    await page.route(`**/api/v1/mine/sites/${siteId}/spatial-regions/current`, route =>
      route.fulfill({
        json: {
          siteId,
          evaluatedAt: NOW,
          nextChangeAt: null,
          items: [{
            zoneType: 'GEOFENCE',
            zoneId: `${label}-zone`,
            zoneVersionId: `${label}-zone-version`,
            coordinateSystemId: `${label}-coordinate`,
            name: `${label}矿装载区`,
            contentHash: `sha256:${'c'.repeat(64)}`,
            validFrom: '2026-01-01T00:00:00Z',
            validTo: null,
            shape: 'POLYGON',
            x: null,
            y: null,
            radiusMeters: null,
            vertices: [
              { sequenceNumber: 1, x: 10, y: 10 },
              { sequenceNumber: 2, x: 90, y: 10 },
              { sequenceNumber: 3, x: 90, y: 90 }
            ]
          }]
        }
      })
    );
    await page.route(new RegExp(`/api/v1/mine/sites/${siteId}/spatial-events\\?.*$`), route =>
      route.fulfill({
        json: {
          siteId,
          from: '2026-07-19T08:00:00Z',
          to: NOW,
          watermark: 1,
          nextCursor: null,
          items: [spatialEvent(siteId, label)]
        }
      })
    );
    await page.route(new RegExp(`/api/v1/mine/sites/${siteId}/alarms\\?.*$`), route =>
      route.fulfill({ json: { items: [alarm(siteId, label)], page: 1, size: 1, total: 1 } })
    );
    await page.route(new RegExp(`/api/v1/mine/sites/${siteId}/shift-instances\\?.*$`), route =>
      route.fulfill({ json: [shift(siteId, label)] })
    );
    await page.route(new RegExp(`/api/v1/mine/sites/${siteId}/work-orders\\?.*$`), route =>
      route.fulfill({ json: [workOrder(siteId, label)] })
    );
  }
}

async function routeCesiumOfflineAssets(page: Page) {
  await page.route(/^https?:\/\/[^/]+\/cesium\//, async route => {
    const marker = '/cesium/';
    const pathname = decodeURIComponent(new URL(route.request().url()).pathname);
    const relativePath = pathname.slice(pathname.indexOf(marker) + marker.length);
    const file = resolve(CESIUM_BUILD_ROOT, relativePath);
    if (!file.startsWith(`${CESIUM_BUILD_ROOT}/`)) {
      await route.fulfill({ status: 403 });
      return;
    }
    try {
      const body = await readFile(file);
      await route.fulfill({ status: 200, body, contentType: contentType(file) });
    } catch {
      await route.fulfill({ status: 404 });
    }
  });
}

async function forceWebGlUnavailable(page: Page) {
  await page.addInitScript(() => {
    const originalGetContext = HTMLCanvasElement.prototype.getContext;
    Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
      configurable: true,
      value(contextId: string, options?: CanvasRenderingContext2DSettings) {
        if (contextId === 'webgl' || contextId === 'webgl2' || contextId === 'experimental-webgl') return null;
        return originalGetContext.call(this, contextId as '2d', options);
      }
    });
  });
}

async function selectSite(page: Page, label: string) {
  await page.locator('.n-select').first().click();
  await page.getByText(label, { exact: true }).last().click();
}

function readyRuntime(siteId: string, layers: ReturnType<typeof layer>[]) {
  return {
    siteId,
    status: 'READY',
    reason: 'NONE',
    snapshot: {
      sceneId: '0198aef6-2e80-7000-8000-000000000020',
      sceneCode: 'demo-open-pit',
      sceneName: '确定性露天矿演示场景',
      versionId: siteId === SITE_A
        ? '0198aef6-2e80-7000-8000-000000000021'
        : '0198aef6-2e80-7000-8000-000000000022',
      revisionNumber: 1,
      schemaVersion: 1,
      contentHash: `sha256:${'a'.repeat(64)}`,
      coordinateSystemId: '0198aef6-2e80-7000-8000-000000000040',
      coordinateMode: 'LOCAL_ENU',
      originX: 0,
      originY: 0,
      originZ: 0,
      headingDegrees: 0,
      pitchDegrees: 0,
      rollDegrees: 0,
      scale: 1,
      defaultCamera: {
        position: [240, 240, 220],
        headingDegrees: 225,
        pitchDegrees: -40,
        rollDegrees: 0
      },
      fallbackViewport: { minX: -150, maxX: 150, minY: -150, maxY: 150, minZ: -60, maxZ: 100 },
      publishedAt: NOW,
      layerCount: layers.length,
      layers
    }
  };
}

function layer(
  layerCode: string,
  orderNumber: number,
  layerType:
    | 'GRID'
    | 'STATIC_GLB'
    | 'DYNAMIC_LOCATIONS'
    | 'DYNAMIC_REGIONS'
    | 'DYNAMIC_ALARMS'
    | 'DYNAMIC_OPERATIONS',
  maxItems: number,
  asset: Record<string, unknown> | null = null,
  options: Record<string, unknown> = {}
) {
  return {
    id: `0198aef6-2e80-7000-8000-${String(orderNumber).padStart(12, '0')}`,
    layerCode,
    orderNumber,
    layerType,
    visible: true,
    opacity: 1,
    minimumDistance: 0,
    maximumDistance: 100_000,
    offsetX: 0,
    offsetY: 0,
    offsetZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    maxItems,
    options,
    asset
  };
}

function gridLayer() {
  return layer('coordinate-grid', 1, 'GRID', 0, null, { width: 300, height: 300, cellsX: 10, cellsY: 10 });
}

function staticGlbLayer(siteId: string, orderNumber = 1) {
  return layer('demo-mine-grid', orderNumber, 'STATIC_GLB', 0, {
    assetId: ASSET_ID,
    assetType: 'GLB',
    contentDigest: 'sha256:4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78',
    fileCount: 1,
    entryUrl: assetEntryUrl(siteId)
  }, { minimumPixelSize: 32 });
}

function dynamicLayers() {
  return [
    layer('live-locations', 1, 'DYNAMIC_LOCATIONS', 100, null, { staleAfterSeconds: 300 }),
    layer('published-regions', 2, 'DYNAMIC_REGIONS', 100, null, { maxEvents: 100 }),
    layer('vehicle-alarms', 3, 'DYNAMIC_ALARMS', 100),
    layer('running-operations', 4, 'DYNAMIC_OPERATIONS', 100)
  ];
}

function assetEntryUrl(siteId: string) {
  return `/api/v1/mine/sites/${siteId}/scene-assets/${ASSET_ID}/files/demo-mine-grid.glb`;
}

function site(id: string, siteCode: string, name: string) {
  return {
    id,
    organizationId: ORGANIZATION_ID,
    siteCode,
    name,
    timezone: 'Asia/Shanghai',
    description: `${name}主采区`,
    status: 'ACTIVE',
    archivedAt: null,
    createdAt: NOW,
    updatedAt: NOW,
    version: 0
  };
}

function location(siteId: string, overrides: Record<string, unknown> = {}) {
  const label = siteId === SITE_A ? 'A' : 'B';
  return {
    eventId: `${label}-location-event`,
    organizationId: ORGANIZATION_ID,
    siteId,
    sourceDeviceId: `${label}-device`,
    tagId: `${label}-tag`,
    tagCode: `${label}-TAG-01`,
    coordinateSystemId: `${label}-coordinate`,
    vehicleId: `${label}-vehicle`,
    personId: null,
    ingestSequence: 1,
    occurredAt: NOW,
    receivedAt: NOW,
    localX: label === 'A' ? 42 : 142,
    localY: label === 'A' ? 36 : 86,
    localZ: 0,
    speedMetersPerSecond: 4.5,
    headingDegrees: 90,
    accuracyMeters: 0.5,
    qualityStatus: 'GOOD',
    qualityReason: null,
    ...overrides
  };
}

function spatialEvent(siteId: string, label: string) {
  return {
    eventId: `${label}-spatial-event`,
    eventSequence: 1,
    organizationId: ORGANIZATION_ID,
    siteId,
    zoneType: 'GEOFENCE',
    zoneId: `${label}-zone`,
    zoneVersionId: `${label}-zone-version`,
    tagId: `${label}-tag`,
    vehicleId: `${label}-vehicle`,
    personId: null,
    eventType: 'ENTER',
    previousState: 'OUTSIDE',
    currentState: 'INSIDE',
    confirmationPoints: 3,
    ingestSequence: 1,
    runtimeRevision: 1,
    occurredAt: NOW,
    anchorLocationEventId: `${label}-location-event`,
    evidence: {
      localX: label === 'A' ? 42 : 142,
      localY: label === 'A' ? 36 : 86,
      localZ: 0,
      classification: 'INSIDE',
      qualityStatus: 'GOOD',
      qualityReason: null,
      zoneContentHash: `sha256:${'c'.repeat(64)}`
    }
  };
}

function alarm(siteId: string, label: string) {
  return {
    id: `${label}-alarm`,
    deviceId: `${label}-device`,
    deviceCode: `${label}-TRUCK-01`,
    deviceName: `${label}矿卡`,
    productKey: 'haul-truck',
    ruleKey: 'engine-temperature-high',
    ruleName: '发动机温度过高',
    sourceType: 'NUMERIC',
    severity: 'CRITICAL',
    propertyId: 'engine.temperature',
    status: 'ACTIVE_UNACKNOWLEDGED',
    policyRuntimeRevision: 1,
    policySnapshotId: `${label}-policy`,
    policyContentHash: `sha256:${'d'.repeat(64)}`,
    raisedAt: NOW,
    raisedEvidence: { value: 112 },
    recoveredAt: null,
    recoveredEvidence: {},
    recoveryReason: null,
    acknowledgedAt: null,
    acknowledgedBy: null,
    acknowledgementNote: null,
    closedAt: null,
    closedBy: null,
    closeNote: null,
    version: 1,
    siteId
  };
}

function shift(siteId: string, label: string) {
  return {
    id: `${label}-shift`,
    organizationId: ORGANIZATION_ID,
    siteId,
    shiftDefinitionId: `${label}-shift-definition`,
    businessDate: '2026-07-20',
    plannedStartAt: '2026-07-20T00:00:00Z',
    plannedEndAt: '2026-07-20T12:00:00Z',
    actualStartAt: '2026-07-20T00:00:00Z',
    actualEndAt: null,
    definitionSnapshot: { name: `${label}矿早班` },
    status: 'OPEN',
    createdAt: NOW,
    updatedAt: NOW,
    version: 1
  };
}

function workOrder(siteId: string, label: string) {
  return {
    id: `${label}-work-order`,
    organizationId: ORGANIZATION_ID,
    siteId,
    shiftInstanceId: `${label}-shift`,
    orderNumber: `${label}-HAUL-01`,
    title: `${label}矿运输`,
    workType: 'HAULAGE',
    plannedStartAt: '2026-07-20T00:00:00Z',
    plannedEndAt: '2026-07-20T12:00:00Z',
    plannedCycles: 5,
    plannedTons: 450,
    priority: 50,
    routeVersionId: null,
    loadGeofenceVersionId: null,
    unloadGeofenceVersionId: null,
    releaseSnapshot: {},
    status: 'RUNNING',
    releasedAt: NOW,
    startedAt: NOW,
    pausedAt: null,
    completedAt: null,
    cancelledAt: null,
    abortedAt: null,
    terminalReason: null,
    createdAt: NOW,
    updatedAt: NOW,
    version: 1,
    assignments: [{
      id: `${label}-assignment`,
      workOrderId: `${label}-work-order`,
      vehicleId: `${label}-vehicle`,
      vehicleCode: `${label}-TRUCK-01`,
      vehicleName: `${label}矿卡`,
      driverPersonId: null,
      driverPersonCode: null,
      driverPersonName: null,
      validFrom: NOW,
      validTo: null,
      status: 'ACTIVE',
      createdAt: NOW,
      updatedAt: NOW,
      version: 1
    }]
  };
}

function ack(request: { action: string; requestId: string }, sequence: number) {
  return { type: 'ACK', action: request.action, requestId: request.requestId, sequence };
}

function contentType(file: string) {
  const extension = extname(file).toLowerCase();
  if (extension === '.js' || extension === '.mjs') return 'text/javascript';
  if (extension === '.json') return 'application/json';
  if (extension === '.css') return 'text/css';
  if (extension === '.wasm') return 'application/wasm';
  if (extension === '.png') return 'image/png';
  if (extension === '.jpg' || extension === '.jpeg') return 'image/jpeg';
  if (extension === '.svg') return 'image/svg+xml';
  return 'application/octet-stream';
}
