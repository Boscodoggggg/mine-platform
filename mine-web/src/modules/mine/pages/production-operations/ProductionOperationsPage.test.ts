import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import ProductionOperationsPage from './ProductionOperationsPage.vue';

const api = vi.hoisted(() => ({
  abortWorkOrder: vi.fn(),
  assignWorkOrder: vi.fn(),
  closeShiftInstance: vi.fn(),
  completeWorkOrder: vi.fn(),
  createShiftInstance: vi.fn(),
  createWorkOrder: vi.fn(),
  getOperationCandidates: vi.fn(),
  getOperationRecord: vi.fn(),
  getOperationRecords: vi.fn(),
  getOperationSummary: vi.fn(),
  getShiftDefinitions: vi.fn(),
  getShiftInstances: vi.fn(),
  getWorkOrders: vi.fn(),
  openShiftInstance: vi.fn(),
  pauseWorkOrder: vi.fn(),
  releaseWorkOrder: vi.fn(),
  resolveOperationCandidate: vi.fn(),
  resumeWorkOrder: vi.fn(),
  startWorkOrder: vi.fn()
}));
const catalogApi = vi.hoisted(() => ({
  getMinePeople: vi.fn(),
  getMineVehicles: vi.fn()
}));
const contextState = vi.hoisted(() => ({
  selectedSiteId: 'site-1' as string | null,
  selectedSite: { id: 'site-1', name: '北区矿', siteCode: 'NORTH', timezone: 'Asia/Shanghai' } as {
    id: string;
    name: string;
    siteCode: string;
    timezone: string;
  } | null,
  sites: [
    { id: 'site-1', name: '北区矿', siteCode: 'NORTH', timezone: 'Asia/Shanghai' },
    { id: 'site-2', name: '南区矿', siteCode: 'SOUTH', timezone: 'Pacific/Honolulu' }
  ],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn((siteId: string) => {
    contextState.selectedSiteId = siteId;
    contextState.selectedSite = contextState.sites.find(site => site.id === siteId) ?? null;
    return true;
  })
}));
const authState = vi.hoisted(() => ({ buttons: [] as string[] }));

vi.mock('@/modules/mine/api/operation', () => api);
vi.mock('@/modules/mine/api/mine-api', () => catalogApi);
vi.mock('@/modules/mine/stores/mine-context', () => ({ useMineContextStore: () => contextState }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    userInfo: {
      get buttons() {
        return authState.buttons;
      }
    }
  })
}));

describe('ProductionOperationsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-20T08:00:00+08:00'));
    setLocale('zh-CN');
    contextState.selectedSiteId = 'site-1';
    contextState.selectedSite = contextState.sites[0]!;
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    contextState.selectSite.mockClear();
    authState.buttons = [
      'mine:operation:view',
      'mine:shift:edit',
      'mine:shift:close',
      'mine:work-order:edit',
      'mine:work-order:dispatch',
      'mine:operation:correct'
    ];
    Object.values(api).forEach(mock => mock.mockReset());
    api.getOperationSummary.mockResolvedValue(summary());
    api.getShiftDefinitions.mockResolvedValue([shiftDefinition()]);
    api.getShiftInstances.mockResolvedValue([shiftInstance()]);
    api.getWorkOrders.mockResolvedValue([workOrder()]);
    api.getOperationCandidates.mockResolvedValue([]);
    api.getOperationRecords.mockResolvedValue({
      siteId: 'site-1',
      fromDate: '2026-07-20',
      toDate: '2026-07-20',
      watermark: 18,
      nextCursor: null,
      items: [recordItem()]
    });
    api.getOperationRecord.mockResolvedValue(recordDetail());
    api.closeShiftInstance.mockResolvedValue({ ...shiftInstance(), status: 'CLOSED', version: 4 });
    api.releaseWorkOrder.mockResolvedValue({ ...workOrder(), status: 'RELEASED', version: 8 });
    api.createShiftInstance.mockResolvedValue({ ...shiftInstance(), status: 'PLANNED', version: 0 });
    api.createWorkOrder.mockResolvedValue(workOrder());
    api.assignWorkOrder.mockResolvedValue({
      ...workOrder(),
      assignments: [{ id: 'assignment-1', vehicleId: 'vehicle-1', driverPersonId: 'driver-1' }],
      version: 8
    });
    catalogApi.getMineVehicles.mockReset().mockResolvedValue({
      items: [{ id: 'vehicle-1', vehicleCode: 'TRUCK-01', name: '1号矿卡', vehicleType: 'HAUL_TRUCK' }],
      page: 0,
      size: 100,
      total: 1
    });
    catalogApi.getMinePeople.mockReset().mockResolvedValue({
      items: [{ id: 'driver-1', personCode: 'DRIVER-01', name: '张师傅', personType: 'DRIVER' }],
      page: 0,
      size: 100,
      total: 1
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
  });

  it('以五个业务页签展示可信运营结论并适配窄屏', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(contextState.refreshContext).toHaveBeenCalledOnce();
    expect(api.getOperationSummary).toHaveBeenCalledWith('site-1', {
      fromDate: '2026-07-20',
      toDate: '2026-07-20'
    });
    expect(wrapper.text()).toContain('运营看板');
    expect(wrapper.text()).toContain('班次');
    expect(wrapper.text()).toContain('作业单');
    expect(wrapper.text()).toContain('待确认');
    expect(wrapper.text()).toContain('执行记录');
    expect(wrapper.text()).toContain('60%');
    expect(wrapper.text()).toContain('12 吨');
    expect(wrapper.text()).toContain('1 趟吨位未知');
    expect(wrapper.get('[data-responsive-stack]').classes()).toContain('flex-col');
    expect(wrapper.get('[data-freshness]').text()).toContain('刚刚更新');
  });

  it('按页签懒加载且矿区切换必须清空旧作用域后重读', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectTab: (tab: string) => Promise<void>;
      changeSite: (siteId: string) => Promise<void>;
    };

    await page.selectTab('shifts');
    await flushPromises();
    expect(api.getShiftDefinitions).toHaveBeenCalledWith('site-1');
    expect(api.getShiftInstances).toHaveBeenCalledWith('site-1', {
      fromDate: '2026-07-20',
      toDate: '2026-07-20'
    });

    await page.changeSite('site-2');
    await flushPromises();
    expect(contextState.selectSite).toHaveBeenCalledWith('site-2');
    expect(api.getShiftDefinitions).toHaveBeenLastCalledWith('site-2');
    expect(api.getShiftInstances).toHaveBeenLastCalledWith('site-2', {
      fromDate: '2026-07-19',
      toDate: '2026-07-19'
    });
  });

  it('矿区切换后迟到的旧作用域响应不得覆盖新矿区', async () => {
    let resolveOld!: (value: ReturnType<typeof summary>) => void;
    api.getOperationSummary
      .mockReset()
      .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ ...summary(), siteId: 'site-2', actualTons: 99 });
    const wrapper = mountPage();
    await flushPromises();

    await (wrapper.vm as unknown as { changeSite: (siteId: string) => Promise<void> }).changeSite('site-2');
    resolveOld({ ...summary(), actualTons: 1 });
    await flushPromises();

    expect(wrapper.text()).toContain('99 吨');
    expect(wrapper.text()).not.toContain('1 吨');
  });

  it('无按钮权限时保持完整只读视图且不渲染写操作', async () => {
    authState.buttons = ['mine:operation:view'];
    const wrapper = mountPage();
    await flushPromises();
    await (wrapper.vm as unknown as { selectTab: (tab: string) => Promise<void> }).selectTab('workOrders');
    await flushPromises();

    expect(wrapper.text()).toContain('当前账号为只读模式');
    expect(wrapper.find('[data-action="release-work-order"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="create-work-order"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('北区运输一班');
  });

  it('班次关账必须展示影响确认并携带冻结乐观版本', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await (wrapper.vm as unknown as { selectTab: (tab: string) => Promise<void> }).selectTab('shifts');
    await flushPromises();
    await wrapper.get('[data-action="close-shift"]').trigger('click');

    expect(window.$dialog?.warning).toHaveBeenCalledOnce();
    const options = vi.mocked(window.$dialog!.warning).mock.calls[0]![0] as { content: string; positiveText: string; onPositiveClick: () => Promise<void> };
    expect(options.content).toContain('未结束作业');
    expect(options.positiveText).toBe('确认关账');
    await options.onPositiveClick();
    expect(api.closeShiftInstance).toHaveBeenCalledWith('site-1', 'shift-1', 3);
  });

  it('可以从有效模板创建当日计划班次', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectTab: (tab: string) => Promise<void>;
      shiftCreateForm: { shiftDefinitionId: string; businessDate: string };
      submitCreateShift: () => Promise<void>;
    };
    await page.selectTab('shifts');
    await flushPromises();

    await wrapper.get('[data-action="create-shift"]').trigger('click');
    expect(document.body.textContent).toContain('新建计划班次');
    page.shiftCreateForm.shiftDefinitionId = 'definition-1';
    page.shiftCreateForm.businessDate = '2026-07-20';
    await page.submitCreateShift();

    expect(api.createShiftInstance).toHaveBeenCalledWith('site-1', {
      shiftDefinitionId: 'definition-1',
      businessDate: '2026-07-20',
      supervisorPersonId: null
    });
  });

  it('可以创建作业计划并从在册车辆与司机完成调度分配', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectTab: (tab: string) => Promise<void>;
      workOrderCreateForm: {
        shiftInstanceId: string;
        orderNumber: string;
        title: string;
        plannedStartAt: string;
        plannedEndAt: string;
        plannedCycles: number | null;
        plannedTons: number | null;
        priority: number;
      };
      assignmentForm: {
        vehicleId: string;
        driverPersonId: string | null;
        validFrom: string;
        validTo: string;
        assignmentReason: string;
      };
      submitCreateWorkOrder: () => Promise<void>;
      submitAssignment: () => Promise<void>;
    };
    await page.selectTab('workOrders');
    await flushPromises();

    await wrapper.get('[data-action="create-work-order"]').trigger('click');
    await flushPromises();
    expect(document.body.textContent).toContain('新建运输作业单');
    Object.assign(page.workOrderCreateForm, {
      shiftInstanceId: 'shift-1',
      orderNumber: 'HAUL-20260720-02',
      title: '北区运输二班',
      plannedStartAt: '2026-07-20T12:00:00Z',
      plannedEndAt: '2026-07-21T00:00:00Z',
      plannedCycles: 10,
      plannedTons: 200,
      priority: 50
    });
    await page.submitCreateWorkOrder();
    expect(api.createWorkOrder).toHaveBeenCalledWith('site-1', {
      shiftInstanceId: 'shift-1',
      orderNumber: 'HAUL-20260720-02',
      title: '北区运输二班',
      workType: 'HAULAGE',
      plannedStartAt: '2026-07-20T12:00:00.000Z',
      plannedEndAt: '2026-07-21T00:00:00.000Z',
      plannedCycles: 10,
      plannedTons: 200,
      priority: 50,
      version: 0
    });

    await wrapper.get('[data-action="assign-work-order"]').trigger('click');
    await flushPromises();
    expect(catalogApi.getMineVehicles).toHaveBeenCalledWith('site-1', { status: 'ACTIVE', page: 0, size: 100 });
    expect(catalogApi.getMinePeople).toHaveBeenCalledWith('site-1', { status: 'ACTIVE', page: 0, size: 100 });
    Object.assign(page.assignmentForm, {
      vehicleId: 'vehicle-1',
      driverPersonId: 'driver-1',
      validFrom: '2026-07-20T12:00:00Z',
      validTo: '2026-07-21T00:00:00Z',
      assignmentReason: '当日运输计划'
    });
    await page.submitAssignment();
    expect(api.assignWorkOrder).toHaveBeenCalledWith('site-1', 'work-order-1', {
      vehicleId: 'vehicle-1',
      driverPersonId: 'driver-1',
      validFrom: '2026-07-20T12:00:00.000Z',
      validTo: '2026-07-21T00:00:00.000Z',
      assignmentReason: '当日运输计划',
      version: 7
    });
  });

  it('执行记录详情必须展示冻结空间版本证据修正历史和未知吨位', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await (wrapper.vm as unknown as { selectTab: (tab: string) => Promise<void> }).selectTab('records');
    await flushPromises();
    await wrapper.get('[data-action="record-details"]').trigger('click');
    await flushPromises();

    expect(api.getOperationRecord).toHaveBeenCalledWith('site-1', 'record-1');
    expect(document.body.textContent).toContain('冻结路线版本');
    expect(document.body.textContent).toContain('route-version-7');
    expect(document.body.textContent).toContain('sha256:route');
    expect(document.body.textContent).toContain('判定证据');
    expect(document.body.textContent).toContain('修正历史');
    expect(document.body.textContent).toContain('吨位未知');
  });

  it('加载失败与空待确认都必须给出可恢复状态', async () => {
    api.getOperationSummary.mockRejectedValueOnce(new Error('offline'));
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('运营数据暂时不可用');
    expect(wrapper.find('[data-action="retry-tab"]').exists()).toBe(true);

    await (wrapper.vm as unknown as { selectTab: (tab: string) => Promise<void> }).selectTab('candidates');
    await flushPromises();
    expect(wrapper.text()).toContain('当前没有待人工确认的运输事件');
    expect(wrapper.text()).toContain('自动识别结果会持续进入这里');
  });

  it('英文环境不得泄漏中文业务文案', async () => {
    setLocale('en-US');
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('Production Operations');
    expect(wrapper.text()).toContain('Operations Overview');
    expect(wrapper.text()).toContain('Work Orders');
    expect(wrapper.text()).not.toContain('生产运营');
  });
});

function mountPage() {
  return mount(ProductionOperationsPage, {
    attachTo: document.body,
    global: { plugins: [i18n] }
  });
}

function shiftDefinition() {
  return {
    id: 'definition-1',
    definitionCode: 'night-shift',
    name: '夜班',
    startLocal: '20:00:00',
    endLocal: '08:00:00',
    crossesMidnight: true,
    status: 'ACTIVE',
    version: 2
  };
}

function shiftInstance() {
  return {
    id: 'shift-1',
    businessDate: '2026-07-20',
    definitionName: '夜班',
    plannedStartAt: '2026-07-20T12:00:00Z',
    plannedEndAt: '2026-07-21T00:00:00Z',
    actualStartAt: '2026-07-20T12:00:00Z',
    actualEndAt: null,
    status: 'OPEN',
    version: 3
  };
}

function workOrder() {
  return {
    id: 'work-order-1',
    orderNumber: 'HAUL-20260720-01',
    title: '北区运输一班',
    workType: 'HAULAGE',
    plannedStartAt: '2026-07-20T12:00:00Z',
    plannedEndAt: '2026-07-21T00:00:00Z',
    plannedCycles: 5,
    plannedTons: 100,
    priority: 50,
    status: 'DRAFT',
    routeVersionId: 'route-version-7',
    loadGeofenceVersionId: 'load-version-3',
    unloadGeofenceVersionId: 'unload-version-4',
    assignments: [],
    version: 7
  };
}

function recordItem() {
  return {
    recordId: 'record-1',
    recordSequence: 17,
    shiftInstanceId: 'shift-1',
    businessDate: '2026-07-20',
    workOrderId: 'work-order-1',
    orderNumber: 'HAUL-20260720-01',
    orderTitle: '北区运输一班',
    assignmentId: 'assignment-1',
    cycleNumber: 4,
    recordStatus: 'COMPLETED',
    vehicleId: 'vehicle-1',
    vehicleCode: 'TRUCK-01',
    vehicleName: '1号矿卡',
    driverPersonId: 'driver-1',
    driverName: '张师傅',
    startedAt: '2026-07-20T20:00:00Z',
    loadCompletedAt: '2026-07-20T20:05:00Z',
    unloadingStartedAt: '2026-07-20T20:15:00Z',
    completedAt: '2026-07-20T20:20:00Z',
    totalDurationSeconds: 1200,
    loadingSeconds: 300,
    haulingSeconds: 600,
    unloadingSeconds: 300,
    distanceMeters: 1000,
    actualTons: null,
    tonsKnown: false,
    tonsSource: null,
    correctionVersion: 1,
    projectedAt: '2026-07-20T20:21:00Z'
  };
}

function recordDetail() {
  return {
    id: 'record-1',
    ...recordItem(),
    frozenSnapshot: {
      routeVersionId: 'route-version-7',
      routeContentHash: 'sha256:route',
      loadGeofenceVersionId: 'load-version-3',
      loadGeofenceContentHash: 'sha256:load',
      unloadGeofenceVersionId: 'unload-version-4',
      unloadGeofenceContentHash: 'sha256:unload',
      vehicleCode: 'TRUCK-01',
      driverName: '张师傅'
    },
    evidence: [
      { id: 'evidence-1', evidenceType: 'SPATIAL_EVENT', sourceEventId: 'event-1', occurredAt: '2026-07-20T20:15:00Z' }
    ],
    corrections: [
      { id: 'correction-1', action: 'AMEND', correctionVersion: 1, reason: '复核磅单', createdAt: '2026-07-20T21:00:00Z' }
    ]
  };
}

function summary() {
  return {
    siteId: 'site-1',
    fromDate: '2026-07-20',
    toDate: '2026-07-20',
    workOrderCount: 1,
    plannedCycles: 5,
    effectiveCompletedCycles: 3,
    anomalyCycles: 1,
    completionRatePercent: 60,
    plannedTons: 100,
    actualTons: 12,
    knownTonsCycles: 2,
    unknownTonsCycles: 1,
    distanceMeters: 3000,
    totalCycleSeconds: 3600,
    averageCycleSeconds: 1200,
    averageLoadingSeconds: 300,
    averageHaulingSeconds: 600,
    averageUnloadingSeconds: 300,
    breakdownsTruncated: false,
    workOrders: [],
    vehicles: [],
    drivers: []
  };
}
