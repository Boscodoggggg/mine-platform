import { nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { i18n } from '@/locales';
import MineCoordinateCanvas from './MineCoordinateCanvas.vue';

const lifecycle = vi.hoisted(() => ({
  width: null as { value: number } | null,
  height: null as { value: number } | null,
  use: vi.fn(),
  init: vi.fn(),
  instance: {
    setOption: vi.fn(), showLoading: vi.fn(), hideLoading: vi.fn(), clear: vi.fn(),
    resize: vi.fn(), dispose: vi.fn()
  }
}));

vi.mock('echarts/core', () => ({
  use: lifecycle.use,
  init: lifecycle.init.mockReturnValue(lifecycle.instance)
}));
vi.mock('@vueuse/core', async () => {
  const { ref } = await import('vue');
  lifecycle.width = ref(0);
  lifecycle.height = ref(0);
  return { useElementSize: () => ({ width: lifecycle.width, height: lifecycle.height }) };
});
vi.mock('@/store/modules/theme', () => ({
  useThemeStore: () => ({ darkMode: false, themeColor: 'theme-primary' })
}));

describe('MineCoordinateCanvas lifecycle', () => {
  it('容器尺寸变化应触发 resize，卸载时必须 dispose', async () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: { items: [location()], trajectory: [], selectedTagId: null, loading: false },
      global: { plugins: [i18n] }
    });

    lifecycle.width!.value = 800;
    lifecycle.height!.value = 560;
    await nextTick();
    await nextTick();
    expect(lifecycle.init).toHaveBeenCalledOnce();

    lifecycle.width!.value = 900;
    await nextTick();
    expect(lifecycle.instance.resize).toHaveBeenCalled();

    wrapper.unmount();
    await nextTick();
    expect(lifecycle.instance.dispose).toHaveBeenCalledOnce();
  });
});

function location() {
  return {
    eventId: 'event-1', organizationId: 'organization-1', siteId: 'site-1', sourceDeviceId: 'device-1',
    tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1', vehicleId: null, personId: null,
    ingestSequence: 10, occurredAt: '2026-07-18T00:00:00Z', receivedAt: '2026-07-18T00:00:01Z',
    localX: 120, localY: 88, localZ: null, speedMetersPerSecond: null, headingDegrees: null,
    accuracyMeters: 0.5, qualityStatus: 'GOOD' as const, qualityReason: null
  };
}
