import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/client";
import { i18n, setLocale } from "@/locales";
import MineSiteArchivePage from "./MineSiteArchivePage.vue";

const api = vi.hoisted(() => ({
  archiveMineOrganization: vi.fn(),
  archiveMineSite: vi.fn(),
  createMineOrganization: vi.fn(),
  createMineSite: vi.fn(),
  getMineOrganizations: vi.fn(),
  getMineSites: vi.fn(),
  updateMineOrganization: vi.fn(),
  updateMineSite: vi.fn(),
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({ refreshContext: vi.fn() }));

vi.mock("@/modules/mine/api/mine-api", () => api);
vi.mock("@/store/modules/auth", () => ({ useAuthStore: () => authState }));
vi.mock("@/modules/mine/stores/mine-context", () => ({
  useMineContextStore: () => contextState,
}));

const organization = {
  id: "organization-1",
  organizationCode: "NORTH",
  name: "华北矿业",
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 2,
};
const site = {
  id: "site-1",
  organizationId: organization.id,
  siteCode: "PIT-A",
  name: "A矿",
  timezone: "Asia/Shanghai",
  description: "主采区",
  status: "ACTIVE",
  archivedAt: null,
  createdAt: "2026-07-18T00:00:00Z",
  updatedAt: "2026-07-18T00:00:00Z",
  version: 3,
};

describe("MineSiteArchivePage", () => {
  beforeEach(() => {
    setLocale("zh-CN");
    Object.values(api).forEach((mock) => mock.mockReset());
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    api.getMineOrganizations.mockResolvedValue({
      items: [organization],
      page: 1,
      size: 20,
      total: 1,
    });
    api.getMineSites.mockResolvedValue({
      items: [site],
      page: 1,
      size: 20,
      total: 1,
    });
    api.updateMineSite.mockResolvedValue(site);
    api.archiveMineSite.mockResolvedValue(undefined);
    window.$message = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
    } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it("应分页加载组织与矿区并按权限显示写入入口", async () => {
    authState.userInfo.buttons = ["mine:organization:edit", "mine:site:edit"];
    const wrapper = mount(MineSiteArchivePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(api.getMineOrganizations).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 20 }),
    );
    expect(api.getMineSites).toHaveBeenCalledWith(
      "organization-1",
      expect.objectContaining({ page: 1, size: 20 }),
    );
    expect(wrapper.text()).toContain("华北矿业");
    expect(wrapper.text()).toContain("A矿");
    expect(wrapper.find('[data-action="create-organization"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-action="create-site"]').exists()).toBe(true);
    expect(wrapper.find("[data-responsive-stack]").exists()).toBe(true);

    const page = wrapper.vm as unknown as {
      changeOrganizationPageSize: (size: number) => Promise<void>;
      changeSitePage: (page: number) => Promise<void>;
    };
    api.getMineOrganizations.mockClear();
    api.getMineOrganizations.mockResolvedValueOnce({
      items: [organization],
      page: 1,
      size: 50,
      total: 1,
    });
    await page.changeOrganizationPageSize(50);
    await flushPromises();
    expect(api.getMineOrganizations).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 50 }),
    );

    api.getMineSites.mockClear();
    api.getMineSites.mockResolvedValueOnce({
      items: [site],
      page: 2,
      size: 20,
      total: 21,
    });
    await page.changeSitePage(2);
    await flushPromises();
    expect(api.getMineSites).toHaveBeenCalledWith(
      "organization-1",
      expect.objectContaining({ page: 2 }),
    );
  });

  it("空档案应给出下一步引导", async () => {
    authState.userInfo.buttons = [];
    api.getMineOrganizations.mockResolvedValue({
      items: [],
      page: 1,
      size: 20,
      total: 0,
    });
    const wrapper = mount(MineSiteArchivePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain("先建立组织，再维护矿区档案");
    expect(wrapper.find('[data-action="create-organization"]').exists()).toBe(
      false,
    );
  });

  it("乐观冲突应提示刷新，归档必须二次确认", async () => {
    authState.userInfo.buttons = ["mine:site:edit"];
    api.updateMineSite.mockRejectedValue(
      new ApiError({ code: "MINE-409-004", message: "版本冲突" }, 409),
    );
    const wrapper = mount(MineSiteArchivePage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openSiteEdit: (value: typeof site) => void;
      saveSite: () => Promise<void>;
      confirmArchiveSite: (value: typeof site) => void;
    };

    page.openSiteEdit(site);
    await page.saveSite();
    expect(window.$message?.warning).toHaveBeenCalledWith(
      "数据已被其他用户修改，已刷新最新内容",
    );

    page.confirmArchiveSite(site);
    const options = vi.mocked(window.$dialog!.warning).mock.calls[0]![0] as {
      onPositiveClick: () => Promise<void>;
    };
    await options.onPositiveClick();
    expect(api.archiveMineSite).toHaveBeenCalledWith("site-1", 3);
  });

  it("加载失败应给出明确反馈且允许保留页面操作", async () => {
    authState.userInfo.buttons = [];
    api.getMineOrganizations.mockRejectedValue(
      new Error("network unavailable"),
    );
    const wrapper = mount(MineSiteArchivePage, {
      global: { plugins: [i18n] },
    });
    await flushPromises();

    expect(window.$message?.error).toHaveBeenCalledWith("操作失败，请稍后重试");
    expect(wrapper.text()).toContain("矿业组织");
  });
});
