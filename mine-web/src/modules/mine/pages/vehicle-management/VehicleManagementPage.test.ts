import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n, setLocale } from "@/locales";
import VehicleManagementPage from "./VehicleManagementPage.vue";

const api = vi.hoisted(() => ({
  archiveMineVehicle: vi.fn(),
  createMineVehicle: vi.fn(),
  getMineVehicles: vi.fn(),
  updateMineVehicle: vi.fn(),
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({
  selectedSiteId: "site-1" as string | null,
  sites: [{ id: "site-1", name: "A矿", siteCode: "PIT-A" }],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn(),
}));

vi.mock("@/modules/mine/api/mine-api", () => api);
vi.mock("@/store/modules/auth", () => ({ useAuthStore: () => authState }));
vi.mock("@/modules/mine/stores/mine-context", () => ({
  useMineContextStore: () => contextState,
}));

const vehicle = {
  id: "vehicle-1",
  organizationId: "organization-1",
  siteId: "site-1",
  vehicleCode: "TRUCK-01",
  name: "1号矿卡",
  vehicleType: "HAUL_TRUCK",
  mineNumber: "M-001",
  licensePlate: null,
  brand: null,
  model: null,
  ratedLoadTons: 90,
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 0,
};

describe("VehicleManagementPage", () => {
  beforeEach(() => {
    setLocale("zh-CN");
    Object.values(api).forEach((mock) => mock.mockReset());
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    contextState.selectedSiteId = "site-1";
    api.getMineVehicles.mockResolvedValue({
      items: [vehicle],
      page: 1,
      size: 20,
      total: 1,
    });
    window.$message = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
    } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it("应以当前矿区加载车辆并在窄屏保留操作", async () => {
    authState.userInfo.buttons = ["mine:vehicle:edit"];
    const wrapper = mount(VehicleManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();

    expect(contextState.refreshContext).toHaveBeenCalled();
    expect(api.getMineVehicles).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ page: 1, size: 20 }),
    );
    expect(wrapper.text()).toContain("1号矿卡");
    expect(wrapper.find('[data-action="create-vehicle"]').exists()).toBe(true);
    expect(wrapper.find("[data-responsive-stack]").classes()).toContain(
      "flex-col",
    );
  });

  it("回车应触发搜索，切换矿区应清空页码后重载", async () => {
    authState.userInfo.buttons = [];
    const wrapper = mount(VehicleManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    api.getMineVehicles.mockClear();

    await wrapper.get(".n-collapse-item__header-main").trigger("click");
    await flushPromises();
    const searchInput = wrapper.get("input");
    await searchInput.setValue("矿卡");
    await searchInput.trigger("keyup.enter");
    await flushPromises();
    expect(api.getMineVehicles).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ keyword: "矿卡", page: 1 }),
    );

    const page = wrapper.vm as unknown as {
      changeSite: (siteId: string) => Promise<void>;
    };
    await page.changeSite("site-2");
    expect(contextState.selectSite).toHaveBeenCalledWith("site-2");
  });
});
