import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { setupIconifyOffline } from '@/plugins/iconify';
import SvgIcon from './svg-icon.vue';

describe('SvgIcon', () => {
  it('应从本地产物渲染主题和菜单图标且不请求 Iconify API', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    setupIconifyOffline();

    const themeIcon = mount(SvgIcon, { props: { icon: 'material-symbols:hdr-auto' } });
    const menuIcon = mount(SvgIcon, { props: { icon: 'mdi:alarm-panel-outline' } });
    const legacyIcon = mount(SvgIcon, { props: { icon: 'ph-caret-double-left-bold' } });

    await vi.waitFor(() => {
      expect(themeIcon.find('svg path').exists()).toBe(true);
      expect(menuIcon.find('svg path').exists()).toBe(true);
      expect(legacyIcon.find('svg path').exists()).toBe(true);
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
