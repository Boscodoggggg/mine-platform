import { describe, expect, it } from 'vitest';
import { hasPermission } from './permission';

describe('hasPermission', () => {
  it('支持单权限、任一权限和全部权限判断', () => {
    const granted = ['device:device:list', 'device:device:create'];

    expect(hasPermission(granted, 'device:device:list')).toBe(true);
    expect(hasPermission(granted, ['device:device:delete', 'device:device:create'])).toBe(true);
    expect(hasPermission(granted, { all: ['device:device:list', 'device:device:create'] })).toBe(true);
    expect(hasPermission(granted, { all: ['device:device:list', 'device:device:delete'] })).toBe(false);
  });

  it('超级管理员通配权限应通过所有检查', () => {
    expect(hasPermission(['*'], 'strategy:package:publish')).toBe(true);
  });
});
