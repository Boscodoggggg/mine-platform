import { expect, test } from '@playwright/test';

test('三种黄金样本通过后应发布、展示节点加载证据并创建回滚发布', async ({ page }) => {
  const backend = await mockProtocolReleaseWorkbench(page, true);

  await page.goto('/device/models');
  await expect(page.getByText('物模型与协议映射', { exact: true })).toBeVisible();

  await page.getByText('协议字段映射', { exact: true }).click();
  await expect(page.getByText('正常扁平遥测')).toBeVisible();
  await expect(page.getByText('正常嵌套遥测')).toBeVisible();
  await expect(page.getByText('缩放和枚举遥测')).toBeVisible();
  await page.locator('[data-action="run-samples"]').click();
  await expect(page.getByText('发布门禁已通过')).toBeVisible();
  await expect(page.getByText('3 / 3')).toBeVisible();

  await page.getByText('发布控制', { exact: true }).click();
  await expect(page.getByText('standalone-gateway')).toBeVisible();
  await expect(page.getByText('已加载')).toBeVisible();

  await page.locator('[data-field="publish-reason"] textarea').fill('验证三种厂商 MQTT JSON 报文');
  await page.locator('[data-action="publish-config"]').click();
  await expect(page.getByRole('button', { name: '发布序号 #8' })).toBeVisible();
  await expect(page.getByText('standalone-gateway')).toBeVisible();
  expect(backend.publishIdempotencyKey).toMatch(/\S+/);

  await page.getByRole('button', { name: '发布序号 #7' }).click();
  await page.getByPlaceholder('填写回滚原因，回滚会创建更高序号的新发布').fill('恢复现场已验证配置');
  await page.locator('[data-action="rollback-release"]').click();
  await page.getByRole('button', { name: '确认回滚' }).click();

  await expect(page.getByRole('button', { name: '发布序号 #9' })).toBeVisible();
  await expect(page.getByText('恢复现场已验证配置').first()).toBeVisible();
  expect(backend.rollbackIdempotencyKey).toMatch(/\S+/);
});

test('黄金样本回归失败时服务端应阻止创建配置发布', async ({ page }) => {
  const backend = await mockProtocolReleaseWorkbench(page, false);

  await page.goto('/device/models');
  await page.getByText('协议字段映射', { exact: true }).click();
  await page.locator('[data-action="run-samples"]').click();
  await expect(page.getByText('发布门禁未通过')).toBeVisible();
  await expect(page.getByText('2 / 3')).toBeVisible();

  await page.getByText('发布控制', { exact: true }).click();
  await page.locator('[data-field="publish-reason"] textarea').fill('错误映射不应进入运行环境');
  await page.locator('[data-action="publish-config"]').click();

  await expect(page.getByText('黄金样本回归未通过，禁止发布')).toBeVisible();
  expect(backend.publishAttempts).toBe(1);
  expect(backend.releases).toHaveLength(1);
});

async function mockProtocolReleaseWorkbench(page: import('@playwright/test').Page, gatePassed: boolean) {
  const state = {
    releases: [release('release-7', 7, 'PUBLISH', '初次接入矿卡协议')],
    publishAttempts: 0,
    publishIdempotencyKey: '',
    rollbackIdempotencyKey: ''
  };

  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: { id: 'user-1', username: 'protocol-admin', displayName: '协议管理员', passwordChangeRequired: false }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['PROTOCOL_ADMIN'],
        permissions: [
          'device:model:view',
          'device:model:edit',
          'device:model:publish',
          'device:protocol:sample:edit',
          'device:protocol:test',
          'device:configuration:view',
          'device:configuration:publish',
          'device:configuration:rollback'
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
                id: 'device-models',
                parentId: 'device-root',
                name: '物模型与协议',
                type: 'MENU',
                path: '/device/models',
                componentKey: 'device-model-management',
                permission: 'device:model:view',
                icon: 'mdi:vector-polyline-edit',
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
  await page.route(/\/api\/v1\/products\?.*$/, route =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'product-1',
            productKey: 'haul-truck',
            name: '露天矿运输车',
            category: 'VEHICLE',
            status: 'ACTIVE',
            description: null,
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
  await page.route('**/api/v1/device-models/products/product-1/configuration', route =>
    route.fulfill({ json: productConfiguration() })
  );
  await page.route('**/api/v1/protocol-mapping-versions/protocol-1/samples', route =>
    route.fulfill({ json: samples() })
  );
  await page.route('**/api/v1/protocol-mapping-versions/protocol-1/test', route =>
    route.fulfill({
      json: {
        id: gatePassed ? 'run-passed' : 'run-failed',
        passed: gatePassed,
        totalSamples: 3,
        passedSamples: gatePassed ? 3 : 2,
        failedSamples: gatePassed ? 0 : 1,
        results: samples().map((sampleItem, index) => ({
          name: sampleItem.name,
          passed: gatePassed || index < 2,
          message: gatePassed || index < 2 ? '通过' : '期望属性不一致',
          actualErrorCode: null
        }))
      }
    })
  );
  await page.route('**/api/v1/config-releases**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (request.method() === 'GET' && path.endsWith('/config-releases')) {
      return route.fulfill({ json: state.releases });
    }
    if (request.method() === 'POST' && path.endsWith('/config-releases')) {
      state.publishAttempts += 1;
      state.publishIdempotencyKey = request.headers()['idempotency-key'] || '';
      if (!gatePassed) {
        return route.fulfill({
          status: 422,
          json: { code: 'CONFIG-422-003', message: '黄金样本回归未通过，禁止发布' }
        });
      }
      const created = release('release-8', 8, 'PUBLISH', '验证三种厂商 MQTT JSON 报文');
      state.releases = [created, ...state.releases];
      return route.fulfill({ json: created });
    }
    const rollbackMatch = path.match(/\/config-releases\/(release-\d+)\/rollback$/);
    if (request.method() === 'POST' && rollbackMatch) {
      state.rollbackIdempotencyKey = request.headers()['idempotency-key'] || '';
      const body = request.postDataJSON() as { reason: string };
      const created = release('release-9', 9, 'ROLLBACK', body.reason, rollbackMatch[1]);
      state.releases = [created, ...state.releases];
      return route.fulfill({ json: created });
    }
    const detailMatch = path.match(/\/config-releases\/(release-\d+)$/);
    if (request.method() === 'GET' && detailMatch) {
      const selected = state.releases.find(item => item.id === detailMatch[1]);
      if (!selected) return route.fulfill({ status: 404, json: { code: 'CONFIG-404-001', message: '发布不存在' } });
      return route.fulfill({
        json: {
          release: selected,
          nodes: [
            {
              nodeId: 'standalone-gateway',
              nodeType: 'GATEWAY',
              status: 'LOADED',
              actualHash: selected.contentHash,
              message: '配置快照已校验、持久化并原子激活',
              loadedAt: '2026-07-16T08:00:03Z',
              updatedAt: '2026-07-16T08:00:03Z'
            }
          ]
        }
      });
    }
    return route.fallback();
  });

  return state;
}

function productConfiguration() {
  return {
    models: [
      {
        version: {
          id: 'model-1',
          definitionId: 'model-definition-1',
          versionNumber: 2,
          status: 'PUBLISHED',
          description: '运输车统一物模型',
          createdAt: '2026-07-16T00:00:00Z',
          publishedAt: '2026-07-16T00:10:00Z',
          version: 1
        },
        fields: [
          {
            id: 'field-1',
            modelVersionId: 'model-1',
            identifier: 'engineRpm',
            nameZh: '发动机转速',
            nameEn: 'Engine RPM',
            dataType: 'INTEGER',
            unit: 'rpm',
            accessMode: 'READ',
            requiredField: true,
            minimumValue: 0,
            maximumValue: 5000,
            decimalPlaces: 0,
            sortOrder: 10,
            description: null,
            version: 0
          }
        ]
      }
    ],
    protocols: [
      {
        version: {
          id: 'protocol-1',
          definitionId: 'protocol-definition-1',
          profileKey: 'haul-truck-json',
          name: '运输车 MQTT JSON',
          versionNumber: 3,
          protocolType: 'MQTT_JSON',
          messageType: 'TELEMETRY',
          parserType: 'CONFIGURATION',
          pluginKey: null,
          pluginVersion: null,
          routingMode: 'TRUSTED_IDENTITY',
          topicOrFrame: 'mine/v1/haul-truck/{clientId}/up/telemetry',
          status: 'PUBLISHED',
          description: '三种厂商样本验证版本',
          createdAt: '2026-07-16T00:00:00Z',
          publishedAt: '2026-07-16T00:20:00Z',
          version: 3
        },
        fields: [
          {
            id: 'mapping-1',
            mappingVersionId: 'protocol-1',
            sourcePath: 'payload.engine.speed',
            sourceDataType: 'INTEGER',
            targetIdentifier: 'engineRpm',
            transformType: 'NONE',
            scaleFactor: null,
            offsetValue: null,
            expression: null,
            requiredField: true,
            sortOrder: 10,
            version: 0
          }
        ]
      }
    ]
  };
}

function samples() {
  return [
    sample('sample-flat', '正常扁平遥测', { rpm: 1280 }, { engineRpm: 1280 }, 10),
    sample('sample-nested', '正常嵌套遥测', { payload: { engine: { speed: 1350 } } }, { engineRpm: 1350 }, 20),
    sample('sample-enum', '缩放和枚举遥测', { values: { currentRaw: 253, stateCode: '1' } }, { motorCurrent: 25.3, runningState: 'RUNNING' }, 30)
  ];
}

function sample(
  id: string,
  name: string,
  rawPayload: Record<string, unknown>,
  expectedProperties: Record<string, unknown>,
  sortOrder: number
) {
  return {
    id,
    mappingVersionId: 'protocol-1',
    name,
    rawPayload,
    expectedSuccess: true,
    expectedProperties,
    expectedErrorCode: null,
    sortOrder,
    version: 0
  };
}

function release(
  id: string,
  releaseSequence: number,
  action: 'PUBLISH' | 'ROLLBACK',
  reason: string,
  rollbackOfReleaseId: string | null = null
) {
  return {
    id,
    productId: 'product-1',
    packageId: `package-${releaseSequence}`,
    releaseSequence,
    action,
    rollbackOfReleaseId,
    contentHash: `sha256:${String(releaseSequence).padStart(64, '0')}`,
    reason,
    createdAt: `2026-07-16T08:00:0${Math.min(releaseSequence, 9)}Z`
  };
}
