import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n, setLocale } from "@/locales";
import PersonManagementPage from "./PersonManagementPage.vue";

const api = vi.hoisted(() => ({
  archiveMinePerson: vi.fn(),
  createMineDriverQualification: vi.fn(),
  createMinePerson: vi.fn(),
  getMineDriverQualifications: vi.fn(),
  getMinePeople: vi.fn(),
  getMinePersonSensitive: vi.fn(),
  revokeMineDriverQualification: vi.fn(),
  updateMinePerson: vi.fn(),
  updateMinePersonSensitive: vi.fn(),
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

const person = {
  id: "person-1",
  organizationId: "organization-1",
  siteId: "site-1",
  personCode: "DRIVER-01",
  name: "张师傅",
  personType: "DRIVER",
  phoneMasked: "138****8000",
  certificateMasked: "41***********1234",
  assignmentFrom: "2026-01-01",
  assignmentTo: null,
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 0,
};

describe("PersonManagementPage", () => {
  beforeEach(() => {
    setLocale("zh-CN");
    Object.values(api).forEach((mock) => mock.mockReset());
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    api.getMinePeople.mockResolvedValue({
      items: [person],
      page: 1,
      size: 20,
      total: 1,
    });
    api.getMinePersonSensitive.mockResolvedValue({
      personId: "person-1",
      phone: "13800008000",
      certificateNumber: "410000199001011234",
    });
    api.getMineDriverQualifications.mockResolvedValue([
      {
        id: "qualification-1",
        personId: "person-1",
        qualificationCode: "A2",
        name: "矿卡驾驶",
        allowedVehicleTypes: ["HAUL_TRUCK"],
        validFrom: "2025-01-01",
        validTo: "2025-12-31",
        status: "EXPIRED",
        revokedAt: null,
        createdAt: "2025-01-01T00:00:00Z",
        updatedAt: "2025-01-01T00:00:00Z",
        version: 0,
      },
    ]);
    window.$message = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
    } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it("人员列表默认只显示脱敏字段且敏感入口独立授权", async () => {
    authState.userInfo.buttons = [];
    const wrapper = mount(PersonManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("138****8000");
    expect(wrapper.text()).not.toContain("13800008000");
    expect(wrapper.find('[data-action="view-sensitive"]').exists()).toBe(false);

    wrapper.unmount();
    authState.userInfo.buttons = ["mine:person:sensitive:view"];
    const authorized = mount(PersonManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    expect(authorized.find('[data-action="view-sensitive"]').exists()).toBe(
      true,
    );
  });

  it("显式读取敏感信息后才展示明文，过期资质应提示", async () => {
    authState.userInfo.buttons = ["mine:person:sensitive:view"];
    const wrapper = mount(PersonManagementPage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openSensitive: (value: typeof person) => Promise<void>;
      openQualifications: (value: typeof person) => Promise<void>;
    };

    await page.openSensitive(person);
    await flushPromises();
    expect(api.getMinePersonSensitive).toHaveBeenCalledWith(
      "site-1",
      "person-1",
    );
    expect(document.body.textContent).toContain("13800008000");

    await page.openQualifications(person);
    await flushPromises();
    expect(document.body.textContent).toContain(
      "该资质已过期，不能用于司机换绑",
    );
    wrapper.unmount();
  });
});
