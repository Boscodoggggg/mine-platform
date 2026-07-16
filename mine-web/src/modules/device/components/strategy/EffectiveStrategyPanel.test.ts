import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import EffectiveStrategyPanel from './EffectiveStrategyPanel.vue';

describe('EffectiveStrategyPanel', () => {
  it('应展示设备最终阈值、逐字段来源与内容哈希', () => {
    setLocale('zh-CN');
    const wrapper = mount(EffectiveStrategyPanel, {
      props: {
        deviceName: '北区运输车 01',
        loading: false,
        effective: {
          deviceId: 'device-1',
          productId: 'product-1',
          strategyType: 'ONLINE_STATUS',
          valid: true,
          values: { offlineThresholdSeconds: 180 },
          sources: {
            offlineThresholdSeconds: {
              scopeType: 'GROUP',
              scopeId: 'group-1',
              releaseId: 'release-8',
              groupPriority: 900
            }
          },
          conflicts: [],
          contentHash: `sha256:${'a'.repeat(64)}`
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('北区运输车 01');
    expect(wrapper.text()).toContain('180');
    expect(wrapper.text()).toContain('设备组覆盖');
    expect(wrapper.text()).toContain('优先级 900');
    expect(wrapper.text()).toContain('release-8');
    expect(wrapper.text()).toContain(`sha256:${'a'.repeat(64)}`);
  });

  it('应显示无效策略冲突和可追踪错误证据', () => {
    setLocale('zh-CN');
    const wrapper = mount(EffectiveStrategyPanel, {
      props: {
        deviceName: '北区运输车 01',
        loading: false,
        effective: {
          deviceId: 'device-1',
          productId: 'product-1',
          strategyType: 'ONLINE_STATUS',
          valid: false,
          values: {},
          sources: {},
          conflicts: [{ field: 'offlineThresholdSeconds', groupPriority: 900, groupIds: ['group-a', 'group-b'] }],
          contentHash: null
        },
        problem: {
          code: 'STRATEGY-409-001',
          message: '同优先级设备组配置冲突',
          traceId: 'trace-001',
          details: { groupIds: ['group-a', 'group-b'] }
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('策略存在冲突');
    expect(wrapper.text()).toContain('STRATEGY-409-001');
    expect(wrapper.text()).toContain('trace-001');
    expect(wrapper.text()).toContain('group-a');
    expect(wrapper.text()).toContain('group-b');
  });
});
