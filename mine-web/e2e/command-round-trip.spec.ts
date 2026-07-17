import { expect, test } from '@playwright/test';

const terminalCases = [
  { status: 'SUCCEEDED', label: '执行成功', errorCode: null, reply: { online: true } },
  { status: 'REFUSED', label: '设备拒绝', errorCode: 'DEVICE_REFUSED', reply: {} },
  { status: 'TIMEOUT', label: '回复超时', errorCode: 'DEVICE_ACK_TIMEOUT', reply: {} }
] as const;

for (const terminalCase of terminalCases) {
  test(`命令中心应展示${terminalCase.label}的真实终态`, async ({ page }) => {
    await mockCommandCenter(page, terminalCase);

    await page.goto('/command/center');
    await expect(page.getByRole('heading', { name: '命令列表', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '详情' }).click();

    await expect(page.getByText(terminalCase.label).last()).toBeVisible();
    if (terminalCase.errorCode) {
      await expect(page.getByText(terminalCase.errorCode)).toBeVisible();
    } else {
      await expect(page.getByText('"online": true')).toBeVisible();
    }
  });
}

test('离开命令中心后应正常显示其他页面', async ({ page }) => {
  await mockCommandCenter(page, terminalCases[0]);

  await page.goto('/command/center');
  await expect(page.getByRole('heading', { name: '命令列表', exact: true })).toBeVisible();
  await page.getByRole('menuitem', { name: '首页' }).click();

  await expect(page).toHaveURL(/\/home$/);
  await expect(page.getByText('矿区运行态势', { exact: true })).toBeVisible();
});

async function mockCommandCenter(page: import('@playwright/test').Page, terminalCase: (typeof terminalCases)[number]) {
  await page.addInitScript(() => {
    localStorage.setItem('MINE_token', JSON.stringify('e2e-token'));
    localStorage.setItem('MINE_tokenName', JSON.stringify('satoken'));
  });
  await page.route('**/api/v1/auth/me', route =>
    route.fulfill({
      json: { id: 'user-1', username: 'dispatcher', displayName: '调度员', passwordChangeRequired: false }
    })
  );
  await page.route('**/api/v1/auth/menus', route =>
    route.fulfill({
      json: {
        roles: ['DISPATCHER'],
        permissions: ['command:instance:view', 'command:instance:create', 'command:template:view'],
        menus: [
          {
            id: 'command-root',
            parentId: null,
            name: '命令管理',
            type: 'DIRECTORY',
            path: '/command',
            componentKey: null,
            permission: null,
            icon: 'mdi:console-network-outline',
            sortOrder: 200,
            visible: true,
            children: [
              {
                id: 'command-center',
                parentId: 'command-root',
                name: '命令中心',
                type: 'MENU',
                path: '/command/center',
                componentKey: 'command-center',
                permission: 'command:instance:view',
                icon: 'mdi:send-clock-outline',
                sortOrder: 10,
                visible: true,
                children: [
                  {
                    id: 'command-template-view',
                    parentId: 'command-center',
                    name: '查看命令模板',
                    type: 'BUTTON',
                    path: null,
                    componentKey: null,
                    permission: 'command:template:view',
                    icon: null,
                    sortOrder: 10,
                    visible: false,
                    children: []
                  },
                  {
                    id: 'command-create',
                    parentId: 'command-center',
                    name: '下发低风险命令',
                    type: 'BUTTON',
                    path: null,
                    componentKey: null,
                    permission: 'command:instance:create',
                    icon: null,
                    sortOrder: 20,
                    visible: false,
                    children: []
                  }
                ]
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
        generatedAt: '2026-07-16T02:30:00Z',
        dataStatus: 'AVAILABLE',
        freshnessSeconds: 300,
        devices: { enabled: 1, reporting: 1, stale: 0, neverReported: 0 },
        telemetry: { todayMessages: 32, latestReceivedAt: '2026-07-16T02:29:58Z', hourly: [] },
        attentionDevices: []
      }
    })
  );
  await page.route(/\/api\/v1\/command-targets\?.*$/, route =>
    route.fulfill({
      json: { items: [{ id: 'device-1', deviceCode: 'truck-001', name: '一号运输车' }], page: 1, size: 100, total: 1 }
    })
  );
  await page.route('**/api/v1/command-templates', route =>
    route.fulfill({
      json: [
        {
          key: 'QUERY_STATUS',
          version: 1,
          riskLevel: 'LOW',
          defaultTtlSeconds: 300,
          defaultTimeoutSeconds: 30,
          parameterSchema: { type: 'object', additionalProperties: false, properties: {} }
        }
      ]
    })
  );
  const command = commandFor(terminalCase);
  await page.route(/\/api\/v1\/commands\?.*$/, route =>
    route.fulfill({ json: { items: [command], page: 1, size: 20, total: 1 } })
  );
  await page.route('**/api/v1/commands/command-1', route => route.fulfill({ json: command }));
}

function commandFor(terminalCase: (typeof terminalCases)[number]) {
  return {
    id: 'command-1',
    deviceId: 'device-1',
    templateKey: 'QUERY_STATUS',
    templateVersion: 1,
    parameters: {},
    riskLevel: 'LOW',
    status: terminalCase.status,
    ttlSeconds: 300,
    timeoutSeconds: 30,
    idempotencyKey: 'request-1',
    createdAt: '2026-07-15T08:00:00Z',
    updatedAt: '2026-07-15T08:00:03Z',
    queuedAt: '2026-07-15T08:00:00Z',
    sentAt: '2026-07-15T08:00:01Z',
    deviceReceivedAt: terminalCase.status === 'TIMEOUT' ? null : '2026-07-15T08:00:02Z',
    completedAt: '2026-07-15T08:00:03Z',
    expiresAt: '2026-07-15T08:05:00Z',
    lastErrorCode: terminalCase.errorCode,
    reply: terminalCase.reply,
    version: 4
  };
}
