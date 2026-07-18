import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { getMineContext } from '../api/mine-api';
import type { MineOrganization, MineSite } from '../api/mine-api';
import { useMineContextStore } from './mine-context';

vi.mock('../api/mine-api', () => ({
  getMineContext: vi.fn()
}));

const organization: MineOrganization = {
  id: '019b0000-0000-7000-8000-000000000001',
  organizationCode: 'ORG-001',
  name: '华北矿业',
  status: 'ACTIVE',
  archivedAt: null,
  createdAt: '2026-07-18T00:00:00Z',
  updatedAt: '2026-07-18T00:00:00Z',
  version: 0
};

const siteA: MineSite = {
  id: '019b0000-0000-7000-8000-000000000101',
  organizationId: organization.id,
  siteCode: 'SITE-A',
  name: 'A矿',
  timezone: 'Asia/Shanghai',
  description: '露天矿',
  status: 'ACTIVE',
  archivedAt: null,
  createdAt: '2026-07-18T00:00:00Z',
  updatedAt: '2026-07-18T00:00:00Z',
  version: 0
};

const siteB = { ...siteA, id: '019b0000-0000-7000-8000-000000000102', siteCode: 'SITE-B', name: 'B矿' };

describe('useMineContextStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    window.$message = { warning: vi.fn(), error: vi.fn() } as never;
  });

  it('只接受服务端当前授权上下文中的矿区', async () => {
    vi.mocked(getMineContext).mockResolvedValue({
      organizations: [{ organization, organizationWide: false, sites: [siteA] }]
    });
    const storageRead = vi.spyOn(Storage.prototype, 'getItem');
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem');
    const store = useMineContextStore();

    await store.refreshContext();
    expect(store.sites).toEqual([siteA]);
    expect(store.selectSite(siteA.id)).toBe(true);
    expect(store.selectedSiteId).toBe(siteA.id);
    expect(store.selectSite(siteB.id)).toBe(false);
    expect(store.selectedSiteId).toBeNull();
    expect(storageRead).not.toHaveBeenCalled();
    expect(storageWrite).not.toHaveBeenCalled();
  });

  it('刷新发现矿区授权被撤销时应清理选择并提示', async () => {
    vi.mocked(getMineContext)
      .mockResolvedValueOnce({
        organizations: [{ organization, organizationWide: true, sites: [siteA, siteB] }]
      })
      .mockResolvedValueOnce({
        organizations: [{ organization, organizationWide: true, sites: [siteB] }]
      });
    const store = useMineContextStore();

    await store.refreshContext();
    store.selectSite(siteA.id);
    await store.refreshContext();

    expect(store.selectedSiteId).toBeNull();
    expect(store.sites).toEqual([siteB]);
    expect(window.$message?.warning).toHaveBeenCalledWith('当前矿区授权已变更，请重新选择矿区');
  });

  it('上下文刷新失败时不应继续使用旧的授权候选', async () => {
    vi.mocked(getMineContext)
      .mockResolvedValueOnce({ organizations: [{ organization, organizationWide: false, sites: [siteA] }] })
      .mockRejectedValueOnce(new Error('网络中断'));
    const store = useMineContextStore();

    await store.refreshContext();
    store.selectSite(siteA.id);
    await expect(store.refreshContext()).rejects.toThrow('网络中断');

    expect(store.organizations).toEqual([]);
    expect(store.sites).toEqual([]);
    expect(store.selectedSiteId).toBeNull();
  });
});
