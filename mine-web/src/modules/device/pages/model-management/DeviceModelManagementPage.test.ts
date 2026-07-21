import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import DeviceModelManagementPage from './DeviceModelManagementPage.vue';

const modelApi = vi.hoisted(() => ({
  createModelDraft: vi.fn(),
  createProtocolDraft: vi.fn(),
  getProductConfiguration: vi.fn(),
  publishModel: vi.fn(),
  publishProtocol: vi.fn(),
  saveModelFields: vi.fn(),
  saveProtocolFields: vi.fn()
}));
const deviceApi = vi.hoisted(() => ({ getProducts: vi.fn() }));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device-model', () => modelApi);
vi.mock('@/modules/device/api/device', () => deviceApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('DeviceModelManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(modelApi).forEach(mock => mock.mockReset());
    deviceApi.getProducts.mockReset();
    deviceApi.getProducts.mockResolvedValue({
      items: [
        {
          id: 'product-1',
          productKey: 'haul-truck',
          name: '矿用运输车',
          deviceType: 'VEHICLE',
          status: 'ACTIVE',
          archivedAt: null,
          createdAt: '2026-07-15T00:00:00Z',
          updatedAt: '2026-07-15T00:00:00Z',
          version: 0
        }
      ],
      page: 1,
      size: 100,
      total: 1
    });
    modelApi.getProductConfiguration.mockResolvedValue({
      models: [
        {
          version: {
            id: 'model-1',
            productId: 'product-1',
            versionNumber: 1,
            status: 'DRAFT',
            description: null,
            publishedAt: null,
            createdAt: '2026-07-15T00:00:00Z',
            updatedAt: '2026-07-15T00:00:00Z',
            version: 0
          },
          fields: [
            {
              identifier: 'engineRpm',
              nameZh: '发动机转速',
              nameEn: 'Engine RPM',
              dataType: 'INTEGER',
              unit: 'rpm',
              accessMode: 'READ',
              requiredField: true,
              minimumValue: 0,
              maximumValue: 5000,
              decimalPlaces: 0,
              sortOrder: 10,
              description: null
            }
          ]
        }
      ],
      protocols: []
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('进入页面时应加载响应式配置字段且不提示操作失败', async () => {
    const wrapper = mount(DeviceModelManagementPage, {
      global: {
        plugins: [i18n],
        stubs: {
          ProtocolSampleWorkbench: true,
          ConfigReleasePanel: true
        }
      }
    });
    await flushPromises();

    const page = wrapper.vm as unknown as { modelFields: Array<{ identifier: string }> };
    expect(page.modelFields.map(field => field.identifier)).toEqual(['engineRpm']);
    expect(window.$message?.error).not.toHaveBeenCalled();
  });
});
