import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import ConfigReleasePanel from './ConfigReleasePanel.vue';

const api = vi.hoisted(() => ({
  getConfigRelease: vi.fn(),
  getConfigReleases: vi.fn(),
  publishConfigRelease: vi.fn(),
  rollbackConfigRelease: vi.fn()
}));

vi.mock('@/modules/device/api/device-model', () => api);

const release = {
  id: 'release-7',
  productId: 'product-1',
  packageId: 'package-7',
  releaseSequence: 7,
  action: 'PUBLISH' as const,
  rollbackOfReleaseId: null,
  contentHash: `sha256:${'a'.repeat(64)}`,
  reason: '接入运输车协议',
  createdAt: '2026-07-16T08:00:00Z'
};

describe('ConfigReleasePanel', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getConfigReleases.mockResolvedValue([release]);
    api.getConfigRelease.mockResolvedValue({
      release,
      nodes: [
        {
          nodeId: 'standalone-gateway',
          nodeType: 'GATEWAY',
          status: 'LOADED',
          actualHash: release.contentHash,
          message: '配置快照已校验并原子激活',
          loadedAt: '2026-07-16T08:00:03Z',
          updatedAt: '2026-07-16T08:00:03Z'
        }
      ]
    });
    api.publishConfigRelease.mockResolvedValue(release);
    api.rollbackConfigRelease.mockResolvedValue({
      ...release,
      id: 'release-8',
      releaseSequence: 8,
      action: 'ROLLBACK'
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应展示发布证据链和节点实际加载状态', async () => {
    const wrapper = mountPanel();
    await flushPromises();

    expect(api.getConfigReleases).toHaveBeenCalledWith('product-1', 50);
    expect(api.getConfigRelease).toHaveBeenCalledWith('release-7');
    expect(wrapper.text()).toContain('发布序号 #7');
    expect(wrapper.text()).toContain('standalone-gateway');
    expect(wrapper.text()).toContain('已加载');
  });

  it('发布与回滚都应携带原因并使用稳定幂等键', async () => {
    const wrapper = mountPanel();
    await flushPromises();

    await wrapper.get('[data-field="publish-reason"] textarea').setValue('验证三种厂商报文');
    await wrapper.get('[data-action="publish-config"]').trigger('click');
    await flushPromises();
    expect(api.publishConfigRelease).toHaveBeenCalledWith(
      {
        productId: 'product-1',
        mappingVersionId: 'protocol-1',
        reason: '验证三种厂商报文'
      },
      expect.any(String)
    );

    const vm = wrapper.vm as unknown as {
      rollbackReason: string;
      runRollback: (item: typeof release) => Promise<void>;
    };
    vm.rollbackReason = '恢复已验证版本';
    await vm.runRollback(release);
    expect(api.rollbackConfigRelease).toHaveBeenCalledWith(
      'release-7',
      '恢复已验证版本',
      expect.any(String)
    );
  });
});

function mountPanel() {
  return mount(ConfigReleasePanel, {
    props: {
      productId: 'product-1',
      mappingVersionId: 'protocol-1',
      protocolPublished: true,
      canView: true,
      canPublish: true,
      canRollback: true
    },
    global: { plugins: [i18n], stubs: { teleport: true } }
  });
}
