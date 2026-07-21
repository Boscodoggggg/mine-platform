import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('在线配置与命令中心 OpenAPI 契约', () => {
  const contract = readFileSync(resolve(process.cwd(), '../docs/openapi/mine-api.yaml'), 'utf8');

  it('应发布菜单和通用字典管理接口', () => {
    expect(contract).toContain('  /system/menus/tree:');
    expect(contract).toContain('  /system/menus/{menuId}:');
    expect(contract).toContain('  /system/dictionaries:');
    expect(contract).toContain('  /dictionaries/{dictionaryCode}/items:');
    expect(contract).toContain('    ManagedMenuNode:');
    expect(contract).toContain('    DictionaryItem:');
  });

  it('应发布物模型和协议字段映射接口', () => {
    expect(contract).toContain('  /device-models/products/{productId}/configuration:');
    expect(contract).toContain('  /device-models/model-versions/{modelVersionId}/publish:');
    expect(contract).toContain('  /device-models/protocol-versions/{protocolVersionId}/publish:');
    expect(contract).toContain('    DeviceModelFieldInput:');
    expect(contract).toContain('    ProtocolMappingFieldInput:');
  });

  it('应发布命令模板筛选和排队命令取消接口', () => {
    expect(contract).toContain('        - name: templateKey');
    expect(contract).toContain('  /commands/{commandId}/cancel:');
    expect(contract).toContain('        requiresReason: { type: boolean }');
  });
});
