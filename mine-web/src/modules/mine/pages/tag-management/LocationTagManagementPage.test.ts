import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NDataTable } from "naive-ui";
import { i18n, setLocale } from "@/locales";
import LocationTagManagementPage from "./LocationTagManagementPage.vue";

const api = vi.hoisted(() => ({
  archiveMineCoordinateSystem: vi.fn(),
  archiveMineLocationTag: vi.fn(),
  createMineCoordinateSystem: vi.fn(),
  createMineCoordinateTransform: vi.fn(),
  createMineLocationTag: vi.fn(),
  getMineCoordinateSystems: vi.fn(),
  getMineCoordinateTransforms: vi.fn(),
  getMineLocationTags: vi.fn(),
  publishMineCoordinateTransform: vi.fn(),
  updateMineCoordinateSystem: vi.fn(),
  updateMineCoordinateTransform: vi.fn(),
  updateMineLocationTag: vi.fn(),
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({
  selectedSiteId: "site-1",
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

const tag = {
  id: "tag-1",
  organizationId: "organization-1",
  siteId: "site-1",
  tagCode: "TAG-01",
  vendor: "华测",
  model: "UWB-A",
  batteryPercent: 76,
  batteryObservedAt: "2026-07-18T00:00:00Z",
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 0,
};
const coordinate = {
  id: "coordinate-1",
  organizationId: "organization-1",
  siteId: "site-1",
  systemCode: "LOCAL-A",
  name: "A矿本地坐标",
  coordinateType: "LOCAL_CARTESIAN",
  unit: "METER",
  epsgCode: null,
  unified: false,
  minX: 0,
  maxX: 5000,
  minY: 0,
  maxY: 3000,
  minZ: null,
  maxZ: null,
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 0,
};
const transform = {
  id: "transform-1",
  organizationId: "organization-1",
  siteId: "site-1",
  sourceCoordinateSystemId: "coordinate-1",
  targetCoordinateSystemId: "coordinate-2",
  versionNumber: 1,
  algorithm: "AFFINE_3D",
  parameters: { matrix: [] },
  validFrom: "2026-07-18T00:00:00Z",
  validTo: null,
  status: "DRAFT",
  contentHash: "sha256:transform",
  publishedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 0,
};

describe("LocationTagManagementPage", () => {
  beforeEach(() => {
    setLocale("zh-CN");
    Object.values(api).forEach((mock) => mock.mockReset());
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    api.getMineLocationTags.mockResolvedValue({
      items: [tag],
      page: 1,
      size: 20,
      total: 1,
    });
    api.getMineCoordinateSystems.mockResolvedValue({
      items: [coordinate],
      page: 1,
      size: 20,
      total: 1,
    });
    api.getMineCoordinateTransforms.mockResolvedValue({
      items: [transform],
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

  it("应加载标签电量与状态，并按权限显示编辑入口", async () => {
    authState.userInfo.buttons = ["mine:location-tag:edit"];
    const wrapper = mount(LocationTagManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();

    expect(api.getMineLocationTags).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ page: 1, size: 20 }),
    );
    expect(wrapper.text()).toContain("TAG-01");
    expect(wrapper.text()).toContain("76%");
    expect(wrapper.find('[data-action="create-tag"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain("坐标模型");

    api.getMineLocationTags.mockClear();
    wrapper.findComponent(NDataTable).vm.$emit("update:page-size", 50);
    await flushPromises();
    expect(api.getMineLocationTags).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ page: 1, size: 50 }),
    );
  });

  it("坐标模型页签应加载双坐标配置且发布权限独立", async () => {
    authState.userInfo.buttons = [
      "mine:coordinate:view",
      "mine:coordinate:publish",
    ];
    const wrapper = mount(LocationTagManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      activateCoordinates: () => Promise<void>;
    };
    await page.activateCoordinates();
    await flushPromises();

    expect(api.getMineCoordinateSystems).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ page: 1 }),
    );
    expect(api.getMineCoordinateTransforms).toHaveBeenCalledWith(
      "site-1",
      expect.objectContaining({ page: 1 }),
    );
    expect(wrapper.text()).toContain("A矿本地坐标");
    expect(wrapper.text()).toContain("三维仿射");
    expect(
      wrapper.find('[data-permission="coordinate-publish"]').exists(),
    ).toBe(true);
  });
});
