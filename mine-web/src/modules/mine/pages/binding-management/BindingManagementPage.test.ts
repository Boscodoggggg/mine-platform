import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n, setLocale } from "@/locales";
import BindingManagementPage from "./BindingManagementPage.vue";

const api = vi.hoisted(() => ({
  getMineLocationTags: vi.fn(),
  getMinePeople: vi.fn(),
  getMineVehicles: vi.fn(),
  previewMineBinding: vi.fn(),
  switchMineBinding: vi.fn(),
}));
const deviceApi = vi.hoisted(() => ({ getDevices: vi.fn() }));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({
  selectedSiteId: "site-1",
  sites: [{ id: "site-1", name: "A矿", siteCode: "PIT-A" }],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn(),
}));

vi.mock("@/modules/mine/api/mine-api", () => api);
vi.mock("@/modules/device/api/device", () => deviceApi);
vi.mock("@/store/modules/auth", () => ({ useAuthStore: () => authState }));
vi.mock("@/modules/mine/stores/mine-context", () => ({
  useMineContextStore: () => contextState,
}));

const preview = {
  operationId: null,
  executed: false,
  executable: true,
  bindingType: "PERSON_VEHICLE",
  previousBinding: null,
  proposedBinding: {
    bindingId: null,
    bindingType: "PERSON_VEHICLE",
    resourceId: "person-1",
    targetId: "vehicle-1",
    role: "DRIVER",
    validFrom: "2026-07-18T00:00:00Z",
    validTo: null,
  },
  qualification: {
    required: true,
    qualified: true,
    qualificationId: "qualification-1",
  },
  affectedObjects: [
    { objectType: "PERSON", objectId: "person-1", impact: "RESOURCE" },
    { objectType: "VEHICLE", objectId: "vehicle-1", impact: "NEW_TARGET" },
  ],
  blockers: [],
  outboxEventId: null,
};

describe("BindingManagementPage", () => {
  beforeEach(() => {
    setLocale("zh-CN");
    Object.values(api).forEach((mock) => mock.mockReset());
    deviceApi.getDevices
      .mockReset()
      .mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    authState.userInfo.buttons = ["mine:binding:switch"];
    api.getMineVehicles.mockResolvedValue({
      items: [{ id: "vehicle-1", vehicleCode: "TRUCK-01", name: "1号矿卡" }],
      page: 1,
      size: 100,
      total: 1,
    });
    api.getMinePeople.mockResolvedValue({
      items: [{ id: "person-1", personCode: "DRIVER-01", name: "张师傅" }],
      page: 1,
      size: 100,
      total: 1,
    });
    api.getMineLocationTags.mockResolvedValue({
      items: [{ id: "tag-1", tagCode: "TAG-01" }],
      page: 1,
      size: 100,
      total: 1,
    });
    api.previewMineBinding.mockResolvedValue(preview);
    api.switchMineBinding.mockResolvedValue({
      ...preview,
      operationId: "operation-1",
      executed: true,
      outboxEventId: "event-1",
    });
    window.$message = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
    } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it("应先预览旧关系、资质与影响对象，不提供历史区间编辑", async () => {
    const wrapper = mount(BindingManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    expect(wrapper.find('[data-action="start-binding"]').exists()).toBe(true);
    const page = wrapper.vm as unknown as {
      form: Record<string, string>;
      previewBinding: () => Promise<void>;
    };
    Object.assign(page.form, {
      bindingType: "PERSON_VEHICLE",
      resourceId: "person-1",
      targetId: "vehicle-1",
      role: "DRIVER",
      effectiveAt: "2026-07-18T08:00:00.000Z",
      reason: "调度班次换车",
    });

    await page.previewBinding();
    await flushPromises();

    expect(api.previewMineBinding).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ bindingType: "PERSON_VEHICLE" }),
    );
    expect(document.body.textContent).toContain("资质校验通过");
    expect(document.body.textContent).toContain("影响对象");
    expect(document.body.textContent).toContain("张师傅 → 1号矿卡");
    expect(document.body.textContent).not.toContain("编辑历史区间");
    wrapper.unmount();
  });

  it("换绑必须二次确认并使用稳定幂等键", async () => {
    const wrapper = mount(BindingManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      form: Record<string, string>;
      previewBinding: () => Promise<void>;
      confirmSwitch: () => void;
    };
    Object.assign(page.form, {
      bindingType: "PERSON_VEHICLE",
      resourceId: "person-1",
      targetId: "vehicle-1",
      role: "DRIVER",
      effectiveAt: "2026-07-18T08:00:00.000Z",
      reason: "调度班次换车",
    });
    await page.previewBinding();
    page.confirmSwitch();
    const options = vi.mocked(window.$dialog!.warning).mock.calls[0]![0] as {
      onPositiveClick: () => Promise<void>;
    };
    await options.onPositiveClick();

    expect(api.switchMineBinding).toHaveBeenCalledWith(
      "site-1",
      expect.any(Object),
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
    );
    expect(window.$message?.success).toHaveBeenCalledWith("换绑已原子生效");
  });

  it("只读用户不应看到发起换绑入口", async () => {
    authState.userInfo.buttons = [];
    const wrapper = mount(BindingManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();

    expect(wrapper.find('[data-action="start-binding"]').exists()).toBe(false);
  });
});
