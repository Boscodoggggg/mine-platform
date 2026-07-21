import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import CommandTimeline from './CommandTimeline.vue';

describe('CommandTimeline', () => {
  it('应按已发生事实展示状态时间线且不伪造未来节点', () => {
    setLocale('zh-CN');
    const wrapper = mount(CommandTimeline, {
      props: {
        command: {
          id: 'command-1',
          deviceId: 'device-1',
          templateKey: 'QUERY_STATUS',
          templateVersion: 1,
          parameters: {},
          dispatchSource: 'BUILTIN_TEMPLATE',
          payloadFormat: 'MQTT_JSON',
          replyExpected: true,
          riskLevel: 'LOW',
          status: 'SENT',
          ttlSeconds: 300,
          timeoutSeconds: 30,
          idempotencyKey: 'request-1',
          createdAt: '2026-07-15T08:00:00Z',
          updatedAt: '2026-07-15T08:00:01Z',
          queuedAt: '2026-07-15T08:00:00Z',
          sentAt: '2026-07-15T08:00:01Z',
          deviceReceivedAt: null,
          completedAt: null,
          expiresAt: '2026-07-15T08:05:00Z',
          lastErrorCode: null,
          reply: null,
          cancelable: false,
          version: 2
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('已排队');
    expect(wrapper.text()).toContain('已发送');
    expect(wrapper.text()).not.toContain('执行成功');
  });

  it.each([
    ['SUCCEEDED', '执行成功'],
    ['REFUSED', '设备拒绝'],
    ['TIMEOUT', '回复超时']
  ])('应展示 %s 终态的真实完成节点', (status, label) => {
    setLocale('zh-CN');
    const wrapper = mount(CommandTimeline, {
      props: {
        command: {
          id: 'command-1',
          deviceId: 'device-1',
          templateKey: 'QUERY_STATUS',
          templateVersion: 1,
          parameters: {},
          dispatchSource: 'BUILTIN_TEMPLATE',
          payloadFormat: 'MQTT_JSON',
          replyExpected: true,
          riskLevel: 'LOW',
          status: status as 'SUCCEEDED' | 'REFUSED' | 'TIMEOUT',
          ttlSeconds: 300,
          timeoutSeconds: 30,
          idempotencyKey: 'request-1',
          createdAt: '2026-07-15T08:00:00Z',
          updatedAt: '2026-07-15T08:00:03Z',
          queuedAt: '2026-07-15T08:00:00Z',
          sentAt: '2026-07-15T08:00:01Z',
          deviceReceivedAt: status === 'TIMEOUT' ? null : '2026-07-15T08:00:02Z',
          completedAt: '2026-07-15T08:00:03Z',
          expiresAt: '2026-07-15T08:05:00Z',
          lastErrorCode: status === 'TIMEOUT' ? 'DEVICE_ACK_TIMEOUT' : null,
          reply: status === 'SUCCEEDED' ? { online: true } : null,
          cancelable: false,
          version: 4
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain(label);
  });
});
