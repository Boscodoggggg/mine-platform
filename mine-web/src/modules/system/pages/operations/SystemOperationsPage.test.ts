import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import SystemOperationsPage from './SystemOperationsPage.vue';

const api = vi.hoisted(() => ({ getSystemOperations: vi.fn() }));

vi.mock('@/modules/system/api/operations', () => api);

describe('SystemOperationsPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    api.getSystemOperations.mockReset();
    api.getSystemOperations.mockResolvedValue({
      status: 'DEGRADED',
      checkedAt: '2026-07-16T03:00:00Z',
      items: [
        { key: 'mine-server', category: 'SERVICE', status: 'UP', latencyMillis: 3, summary: 'serviceUp' },
        { key: 'rabbitmq', category: 'MESSAGE', status: 'DOWN', latencyMillis: 50, summary: 'healthFailed' },
        { key: 'timescale', category: 'STORAGE', status: 'UP', latencyMillis: 8, summary: 'connectionUp' }
      ]
    });
  });

  it('应按服务消息和存储分组展示按需检查结果', async () => {
    const wrapper = mount(SystemOperationsPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('运行监控');
    expect(wrapper.text()).toContain('平台服务');
    expect(wrapper.text()).toContain('消息中枢');
    expect(wrapper.text()).toContain('存储与缓存');
    expect(wrapper.text()).toContain('管理服务');
    expect(wrapper.text()).toContain('RabbitMQ');
    expect(wrapper.text()).toContain('异常');
    expect(api.getSystemOperations).toHaveBeenCalledTimes(1);
  });
});
