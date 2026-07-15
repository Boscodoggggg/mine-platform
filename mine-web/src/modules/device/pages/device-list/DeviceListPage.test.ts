import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import DeviceListPage from './DeviceListPage.vue';

const api = vi.hoisted(() => ({
  archiveDevice: vi.fn(),
  createDevice: vi.fn(),
  getDevices: vi.fn(),
  getProducts: vi.fn(),
  updateDevice: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const product = {
  id: 'product-1',
  productKey: 'haul-truck',
  name: '矿用运输车',
  deviceType: 'VEHICLE',
  status: 'ACTIVE' as const,
  archivedAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0
};
const device = {
  id: 'device-1',
  productId: 'product-1',
  deviceCode: 'truck-001',
  name: '一号运输车',
  status: 'ENABLED' as const,
  archivedAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0
};

describe('DeviceListPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getProducts.mockResolvedValue({ items: [product], page: 1, size: 100, total: 1 });
    api.getDevices.mockResolvedValue({ items: [device], page: 1, size: 20, total: 1 });
    api.createDevice.mockResolvedValue(device);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应显示设备与所属产品并按权限显示创建入口', async () => {
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();

    expect(wrapper.text()).toContain('设备台账');
    expect(wrapper.text()).toContain('一号运输车');
    expect(wrapper.text()).toContain('矿用运输车');
    expect(wrapper.find('[data-action="create-device"]').exists()).toBe(true);
  });

  it('创建设备时应提交产品标识和矿区内设备编码', async () => {
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      deviceForm: Record<string, string>;
      openCreate: () => void;
      saveDevice: () => Promise<void>;
    };

    page.openCreate();
    Object.assign(page.deviceForm, {
      productId: 'product-1',
      deviceCode: 'truck-002',
      name: '二号运输车',
      status: 'ENABLED'
    });
    await page.saveDevice();

    expect(api.createDevice).toHaveBeenCalledWith({
      productId: 'product-1',
      deviceCode: 'truck-002',
      name: '二号运输车',
      status: 'ENABLED'
    });
  });

  it('创建设备时只能选择已发布产品', async () => {
    api.getProducts.mockResolvedValue({
      items: [{ ...product, status: 'DRAFT', id: 'product-draft', productKey: 'draft-truck' }, product],
      page: 1,
      size: 100,
      total: 2
    });
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();

    const page = wrapper.vm as unknown as {
      creatableProductOptions: Array<{ value: string }>;
    };
    expect(page.creatableProductOptions.map(option => option.value)).toEqual(['product-1']);
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(DeviceListPage, { global: { plugins: [i18n] } });
}
