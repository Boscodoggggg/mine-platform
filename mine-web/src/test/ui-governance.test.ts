import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parse } from '@vue/compiler-sfc';
import { describe, expect, it } from 'vitest';

const businessPages = [
  'src/modules/system/pages/login/LoginPage.vue',
  'src/modules/system/pages/users/UserManagementPage.vue',
  'src/modules/system/pages/roles/RoleManagementPage.vue',
  'src/modules/system/pages/operations/SystemOperationsPage.vue',
  'src/modules/system/pages/menus/MenuManagementPage.vue',
  'src/modules/system/pages/dictionaries/DictionaryManagementPage.vue',
  'src/modules/device/pages/product-list/ProductListPage.vue',
  'src/modules/device/pages/device-list/DeviceListPage.vue',
  'src/modules/device/pages/model-management/DeviceModelManagementPage.vue',
  'src/modules/monitoring/pages/device-monitor/DeviceMonitorPage.vue',
  'src/modules/monitoring/components/NumericCard.vue',
  'src/modules/command/pages/command-center/CommandCenterPage.vue',
  'src/modules/command/components/CommandTimeline.vue',
  'src/views/home/index.vue',
  'src/app/router/DynamicFallbackPage.vue'
];

function readPage(relativePath: string) {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8');
}

describe('前端主题与国际化治理', () => {
  it.each(businessPages)('%s 不应定义页面私有样式或硬编码颜色', relativePath => {
    const source = readPage(relativePath);

    expect(source).not.toMatch(/<style\b/i);
    expect(source).not.toMatch(/#[\da-f]{3,8}\b/i);
    expect(source).not.toMatch(/\b(?:rgb|rgba|hsl|hsla)\s*\(/i);
    expect(source).not.toMatch(/(?:bg|text|border)-\[?#/i);
  });

  it.each(businessPages)('%s 的用户可见文案应来自国际化资源', relativePath => {
    const source = readPage(relativePath);

    expect(source).toContain('useI18n');
    expect(source).not.toMatch(/[\u3400-\u9fff]/u);
  });

  it('登录页应复用全局主题模式，不维护页面私有主题状态', () => {
    const source = readPage('src/modules/system/pages/login/LoginPage.vue');

    expect(source).not.toContain('mine-login-theme');
    expect(source).not.toContain('matchMedia');
    expect(source).not.toContain('setThemeScheme');
    expect(source).toContain('themeStore.themeScheme');
  });

  it('运行监控页在全局路由过渡下应只有一个元素根节点', () => {
    const source = readPage('src/modules/system/pages/operations/SystemOperationsPage.vue');
    const { descriptor } = parse(source);
    const meaningfulRoots = descriptor.template?.ast?.children.filter(node => {
      if (node.type === 3) return false;
      return node.type !== 2 || node.content.trim().length > 0;
    }) || [];

    expect(meaningfulRoots).toHaveLength(1);
    expect(meaningfulRoots[0]).toMatchObject({ type: 1, tag: 'div' });
  });
});
