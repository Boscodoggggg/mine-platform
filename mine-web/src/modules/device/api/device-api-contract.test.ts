import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('设备管理 OpenAPI 契约', () => {
  const contract = readFileSync(resolve(process.cwd(), '../docs/openapi/mine-api.yaml'), 'utf8');

  it('应同时发布产品、设备和遥测接口', () => {
    expect(contract).toContain('  /products:');
    expect(contract).toContain('  /products/{productId}:');
    expect(contract).toContain('  /devices:');
    expect(contract).toContain('  /devices/{deviceId}:');
    expect(contract).toContain('  /devices/{deviceId}/latest:');
  });

  it('应发布产品设备分页和写请求模型', () => {
    expect(contract).toContain('    ProductPage:');
    expect(contract).toContain('    ProductCreateRequest:');
    expect(contract).toContain('    DevicePage:');
    expect(contract).toContain('    DeviceCreateRequest:');
  });
});
