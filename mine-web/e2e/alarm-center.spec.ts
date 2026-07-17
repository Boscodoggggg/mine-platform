import { expect, test } from '@playwright/test';

test('调度员应从默认待处置列表确认告警并提交稳定幂等事实', async ({ page }) => {
  const backend = await mockAlarmCenter(page, 'ACTIVE_UNACKNOWLEDGED');

  await page.goto('/alarm/center');
  await expect(page.getByText('告警处置中心', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('活动 · 未确认', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('发动机温度过高', { exact: true }).first()).toBeVisible();
  expect(backend.firstListQuery?.get('closed')).toBe('false');
  expect(backend.firstListQuery?.has('status')).toBe(false);

  await page.getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('触发证据', { exact: true })).toBeVisible();
  await expect(page.getByText(/"value": 112/)).toBeVisible();
  await page.getByPlaceholder('填写现场联系、排查结论或关闭依据').fill('调度已通知现场检查');
  await page.locator('[data-action="acknowledge-alarm"]').click();
  await page.getByRole('button', { name: '确认告警', exact: true }).last().click();

  await expect(page.getByText('活动 · 已确认', { exact: true }).first()).toBeVisible();
  expect(backend.acknowledgementAttempts).toBe(1);
  expect(backend.acknowledgementKey).toMatch(/^alarm-/);
  expect(backend.acknowledgementBody).toEqual({ note: '调度已通知现场检查', version: 3 });
});

test('仅已恢复告警应允许关闭并保留恢复证据', async ({ page }) => {
  const backend = await mockAlarmCenter(page, 'RECOVERED_ACKNOWLEDGED');

  await page.goto('/alarm/center');
  await page.getByText('搜索', { exact: true }).click();
  await page.locator('[data-alarm-filter-row] .n-select').first().click();
  await page.getByText('已恢复 · 已确认', { exact: true }).last().click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.getByRole('button', { name: '详情' }).click();
  await expect(page.getByText('恢复证据', { exact: true })).toBeVisible();
  await page.getByPlaceholder('填写现场联系、排查结论或关闭依据').fill('现场复核温度已恢复');
  await page.locator('[data-action="close-alarm"]').click();
  await page.getByRole('button', { name: '关闭告警', exact: true }).last().click();

  await expect(page.locator('.n-drawer').getByText('已关闭', { exact: true })).toBeVisible();
  expect(backend.closeAttempts).toBe(1);
  expect(backend.closeKey).toMatch(/^alarm-/);
  expect(backend.closeBody).toEqual({ note: '现场复核温度已恢复', version: 3 });
});

async function mockAlarmCenter(page: import('@playwright/test').Page, initialStatus: AlarmFixtureStatus) {
  const state = {
    alarm: alarm(initialStatus) as MutableAlarmFixture,
    firstListQuery: null as URLSearchParams | null,
    acknowledgementAttempts: 0,
    acknowledgementKey: '',
    acknowledgementBody: null as Record<string, unknown> | null,
    closeAttempts: 0,
    closeKey: '',
    closeBody: null as Record<string, unknown> | null
  };

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
        permissions: ['alarm:event:view', 'alarm:event:ack', 'alarm:event:close'],
        menus: [
          {
            id: 'alarm-root',
            parentId: null,
            name: '告警管理',
            type: 'DIRECTORY',
            path: '/alarm',
            componentKey: null,
            permission: null,
            icon: 'mdi:alarm-light-outline',
            sortOrder: 150,
            visible: true,
            i18nKey: 'mine.menu.alarms',
            children: [
              {
                id: 'alarm-center',
                parentId: 'alarm-root',
                name: '告警中心',
                type: 'MENU',
                path: '/alarm/center',
                componentKey: 'alarm-center',
                permission: 'alarm:event:view',
                icon: 'mdi:alarm-panel-outline',
                sortOrder: 10,
                visible: true,
                i18nKey: 'mine.menu.alarmCenter',
                children: [
                  button('alarm-ack', '确认告警', 'alarm:event:ack'),
                  button('alarm-close', '关闭告警', 'alarm:event:close')
                ]
              }
            ]
          }
        ]
      }
    })
  );
  await page.route(/\/api\/v1\/alarms\?.*$/, route => {
    const parameters = new URL(route.request().url()).searchParams;
    state.firstListQuery ||= parameters;
    const matches = !parameters.get('status') || parameters.get('status') === state.alarm.status;
    return route.fulfill({
      json: { items: matches ? [state.alarm] : [], page: 1, size: 20, total: matches ? 1 : 0 }
    });
  });
  await page.route('**/api/v1/alarms/alarm-1', route => route.fulfill({ json: state.alarm }));
  await page.route('**/api/v1/alarms/alarm-1/acknowledgements', route => {
    state.acknowledgementAttempts += 1;
    state.acknowledgementKey = route.request().headers()['idempotency-key'] || '';
    state.acknowledgementBody = route.request().postDataJSON() as Record<string, unknown>;
    state.alarm = {
      ...state.alarm,
      status: 'ACTIVE_ACKNOWLEDGED',
      acknowledgedAt: '2026-07-17T01:05:00Z',
      acknowledgedBy: 'user-1',
      acknowledgementNote: String(state.acknowledgementBody.note),
      version: 4
    };
    return route.fulfill({ json: state.alarm });
  });
  await page.route('**/api/v1/alarms/alarm-1/close', route => {
    state.closeAttempts += 1;
    state.closeKey = route.request().headers()['idempotency-key'] || '';
    state.closeBody = route.request().postDataJSON() as Record<string, unknown>;
    state.alarm = {
      ...state.alarm,
      status: 'CLOSED',
      closedAt: '2026-07-17T01:08:00Z',
      closedBy: 'user-1',
      closeNote: String(state.closeBody.note),
      version: 4
    };
    return route.fulfill({ json: state.alarm });
  });

  return state;
}

type AlarmFixtureStatus = 'ACTIVE_UNACKNOWLEDGED' | 'RECOVERED_ACKNOWLEDGED';
type MutableAlarmFixture = Omit<
  ReturnType<typeof alarm>,
  'status' | 'acknowledgedAt' | 'acknowledgedBy' | 'acknowledgementNote' | 'closedAt' | 'closedBy' | 'closeNote'
> & {
  status: AlarmFixtureStatus | 'ACTIVE_ACKNOWLEDGED' | 'CLOSED';
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  acknowledgementNote: string | null;
  closedAt: string | null;
  closedBy: string | null;
  closeNote: string | null;
};

function alarm(status: AlarmFixtureStatus) {
  const recovered = status === 'RECOVERED_ACKNOWLEDGED';
  return {
    id: 'alarm-1',
    deviceId: 'device-1',
    deviceCode: 'TRUCK-001',
    deviceName: '北区运输车 01',
    productKey: 'haul-truck',
    ruleKey: 'engine-temperature-high',
    ruleName: '发动机温度过高',
    sourceType: 'NUMERIC',
    severity: 'CRITICAL',
    propertyId: 'engine.temperature',
    status,
    policyRuntimeRevision: 5,
    policySnapshotId: 'snapshot-1',
    policyContentHash: `sha256:${'a'.repeat(64)}`,
    raisedAt: '2026-07-17T01:00:00Z',
    raisedEvidence: { value: 112, threshold: 110 },
    recoveredAt: recovered ? '2026-07-17T01:03:00Z' : null,
    recoveredEvidence: recovered ? { value: 98, recoverThreshold: 100 } : {},
    recoveryReason: recovered ? 'CONDITION_CLEARED' : null,
    acknowledgedAt: recovered ? '2026-07-17T01:01:00Z' : null,
    acknowledgedBy: recovered ? 'user-2' : null,
    acknowledgementNote: recovered ? '已联系现场' : null,
    closedAt: null,
    closedBy: null,
    closeNote: null,
    version: 3
  };
}

function button(id: string, name: string, permission: string) {
  return {
    id,
    parentId: 'alarm-center',
    name,
    type: 'BUTTON',
    path: null,
    componentKey: null,
    permission,
    icon: null,
    sortOrder: 10,
    visible: false,
    children: []
  };
}
