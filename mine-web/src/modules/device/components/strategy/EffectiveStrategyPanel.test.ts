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
          conflicts: [
            {
              field: 'offlineThresholdSeconds',
              groupPriority: 900,
              groupIds: ['group-a', 'group-b']
            }
          ],
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

  it('应逐字段展示数据质量有效值及其发布来源', () => {
    setLocale('zh-CN');
    const source = {
      scopeType: 'DEVICE' as const,
      scopeId: 'device-1',
      releaseId: 'quality-release-3',
      groupPriority: null
    };
    const wrapper = mount(EffectiveStrategyPanel, {
      props: {
        deviceName: '北区运输车 01',
        loading: false,
        effective: {
          deviceId: 'device-1',
          productId: 'product-1',
          strategyType: 'DATA_QUALITY',
          valid: true,
          values: {
            staleAfterSeconds: 120,
            'required.engine.rpm': true,
            'minimum.engine.rpm': 500
          },
          sources: {
            staleAfterSeconds: source,
            'required.engine.rpm': source,
            'minimum.engine.rpm': source
          },
          conflicts: [],
          contentHash: `sha256:${'c'.repeat(64)}`
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('陈旧阈值');
    expect(wrapper.text()).toContain('必填 · engine.rpm');
    expect(wrapper.text()).toContain('下限 · engine.rpm');
    expect(wrapper.text()).toContain('单设备覆盖');
    expect(wrapper.text()).toContain('quality-release-3');
  });

  it('应展示最终生效的告警规则、严重度、迟滞阈值与原子来源', () => {
    setLocale('zh-CN');
    const wrapper = mount(EffectiveStrategyPanel, {
      props: {
        deviceName: '北区运输车 01',
        loading: false,
        effective: {
          deviceId: 'device-1',
          productId: 'product-1',
          strategyType: 'ALARM',
          valid: true,
          values: {
            rules: [
              {
                key: 'engine-temperature-high',
                name: '发动机温度过高',
                sourceType: 'NUMERIC',
                severity: 'CRITICAL',
                triggerDurationSeconds: 10,
                recoverDurationSeconds: 30,
                cooldownSeconds: 300,
                enabled: false,
                propertyId: 'engine.temperature',
                operator: 'GT',
                threshold: 110,
                recoverThreshold: 100
              }
            ]
          },
          sources: {
            rules: {
              scopeType: 'GROUP',
              scopeId: 'group-1',
              releaseId: 'alarm-release-5',
              groupPriority: 900
            }
          },
          conflicts: [],
          contentHash: `sha256:${'d'.repeat(64)}`
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('1 条告警规则');
    expect(wrapper.text()).toContain('发动机温度过高');
    expect(wrapper.text()).toContain('严重');
    expect(wrapper.text()).toContain('停用');
    expect(wrapper.text()).toContain('engine.temperature');
    expect(wrapper.text()).toContain('恢复阈值 100');
    expect(wrapper.text()).toContain('触发 10 秒');
    expect(wrapper.text()).toContain('恢复 30 秒');
    expect(wrapper.text()).toContain('冷却 300 秒');
    expect(wrapper.text()).toContain('alarm-release-5');
  });

  it('应整体展示最终保留策略及其单一发布来源', () => {
    setLocale('zh-CN');
    const wrapper = mount(EffectiveStrategyPanel, {
      props: {
        deviceName: '北区运输车 01',
        loading: false,
        effective: {
          deviceId: 'device-1',
          productId: 'product-1',
          strategyType: 'RETENTION',
          valid: true,
          values: {
            policy: {
              enabled: true,
              retentionClass: 'STANDARD',
              rawTelemetryDays: 90,
              qualityEvidenceDays: 180
            }
          },
          sources: {
            policy: {
              scopeType: 'PRODUCT',
              scopeId: 'product-1',
              releaseId: 'retention-release-2',
              groupPriority: null
            }
          },
          conflicts: [],
          contentHash: `sha256:${'e'.repeat(64)}`
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('数据保留策略');
    expect(wrapper.text()).toContain('标准');
    expect(wrapper.text()).toContain('原始遥测 90 天');
    expect(wrapper.text()).toContain('质量证据 180 天');
    expect(wrapper.text()).toContain('产品默认');
    expect(wrapper.text()).toContain('retention-release-2');
    expect(wrapper.text()).not.toContain('[object Object]');
  });
});
