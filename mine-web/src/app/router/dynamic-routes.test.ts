import { describe, expect, it } from 'vitest';
import { setLocale } from '@/locales';
import { buildDynamicNavigation } from './dynamic-routes';
import type { NavigationMenuNode } from '@/modules/system/api/auth';

const nodes: NavigationMenuNode[] = [
  {
    id: 'directory-1',
    parentId: null,
    name: '设备管理',
    type: 'DIRECTORY',
    path: '/device',
    componentKey: null,
    permission: null,
    icon: 'mdi:factory',
    sortOrder: 1,
    visible: true,
    children: [
      {
        id: 'menu-known',
        parentId: 'directory-1',
        name: '运行总览',
        type: 'MENU',
        path: '/device/overview',
        componentKey: 'home-overview',
        permission: null,
        icon: 'mdi:monitor-dashboard',
        sortOrder: 1,
        visible: true,
        children: [
          {
            id: 'button-1',
            parentId: 'menu-known',
            name: '查看详情',
            type: 'BUTTON',
            path: null,
            componentKey: null,
            permission: 'device:device:list',
            icon: null,
            sortOrder: 1,
            visible: true,
            children: []
          }
        ]
      },
      {
        id: 'menu-unknown',
        parentId: 'directory-1',
        name: '厂商页面',
        type: 'MENU',
        path: '/device/vendor',
        componentKey: 'vendor-arbitrary-file',
        permission: null,
        icon: null,
        sortOrder: 2,
        visible: true,
        children: []
      }
    ]
  }
];

describe('buildDynamicNavigation', () => {
  it('只为MENU生成路由且未知组件降级到本地占位页', () => {
    const result = buildDynamicNavigation(nodes);

    expect(result.routes).toHaveLength(2);
    expect(result.routes.map(route => route.path)).toEqual(['/device/overview', '/device/vendor']);
    expect(result.routes[0]?.meta?.componentFallback).toBe(false);
    expect(result.routes[1]?.meta?.componentFallback).toBe(true);
    expect(result.unknownComponentKeys).toEqual(['vendor-arbitrary-file']);
    expect(result.routes.some(route => route.path === 'device:device:list')).toBe(false);
  });

  it('目录保留层级而按钮不进入导航菜单', () => {
    const result = buildDynamicNavigation(nodes);

    expect(result.menus).toHaveLength(1);
    expect(result.menus[0]?.label).toBe('设备管理');
    expect(result.menus[0]?.children).toHaveLength(2);
    expect(result.menus[0]?.children?.some(menu => menu.label === '查看详情')).toBe(false);
  });

  it('系统用户角色和运行监控组件键应命中本地白名单', () => {
    const systemNodes: NavigationMenuNode[] = [
      {
        ...nodes[0],
        id: 'system-directory',
        name: '系统管理',
        children: [
          {
            ...nodes[0].children[0],
            id: 'system-users',
            name: '用户管理',
            path: '/system/users',
            componentKey: 'system-user-list',
            children: []
          },
          {
            ...nodes[0].children[0],
            id: 'system-roles',
            name: '角色管理',
            path: '/system/roles',
            componentKey: 'system-role-list',
            children: []
          },
          {
            ...nodes[0].children[0],
            id: 'system-operations',
            name: '运行监控',
            path: '/system/operations',
            componentKey: 'system-operations-monitor',
            children: []
          }
        ]
      }
    ];

    const result = buildDynamicNavigation(systemNodes);

    expect(result.routes).toHaveLength(3);
    expect(result.routes.every(route => route.meta?.componentFallback === false)).toBe(true);
    expect(result.unknownComponentKeys).toEqual([]);
  });

  it('设备产品台账和监控组件键应命中本地白名单并提供国际化菜单', () => {
    const deviceNodes: NavigationMenuNode[] = [
      {
        ...nodes[0],
        children: [
          {
            ...nodes[0].children[0],
            id: 'device-products',
            name: '产品管理',
            path: '/device/products',
            componentKey: 'device-product-list',
            children: []
          },
          {
            ...nodes[0].children[0],
            id: 'device-devices',
            name: '设备台账',
            path: '/device/devices',
            componentKey: 'device-device-list',
            children: []
          },
          {
            ...nodes[0].children[0],
            id: 'device-monitor',
            name: '设备监控',
            path: '/device/monitor',
            componentKey: 'device-monitor',
            children: []
          }
        ]
      }
    ];

    setLocale('en-US');
    const result = buildDynamicNavigation(deviceNodes);
    setLocale('zh-CN');

    expect(result.routes).toHaveLength(3);
    expect(result.routes.every(route => route.meta?.componentFallback === false)).toBe(true);
    expect(result.unknownComponentKeys).toEqual([]);
    expect(result.menus[0]?.children?.map(menu => menu.label)).toEqual([
      'Product Management',
      'Device Registry',
      'Device Monitoring'
    ]);
  });

  it('已注册动态菜单应生成国际化键并按当前语言显示', () => {
    const systemNodes: NavigationMenuNode[] = [
      {
        ...nodes[0],
        id: 'system-directory',
        name: '系统管理',
        path: '/system',
        children: [
          {
            ...nodes[0].children[0],
            id: 'system-users',
            name: '用户管理',
            path: '/system/users',
            componentKey: 'system-user-list',
            children: []
          }
        ]
      }
    ];

    setLocale('en-US');
    const result = buildDynamicNavigation(systemNodes);
    setLocale('zh-CN');

    expect(result.menus[0]).toMatchObject({
      i18nKey: 'mine.menu.system',
      label: 'System Management'
    });
    expect(result.menus[0]?.children?.[0]).toMatchObject({
      i18nKey: 'mine.menu.users',
      label: 'User Management'
    });
    expect(result.routes[0]?.children?.[0]?.meta).toMatchObject({
      i18nKey: 'mine.menu.users',
      title: '用户管理'
    });
  });

  it('命令中心组件键应命中白名单并提供国际化菜单', () => {
    const commandNodes: NavigationMenuNode[] = [
      {
        ...nodes[0],
        id: 'command-directory',
        name: '命令管理',
        path: '/command',
        children: [
          {
            ...nodes[0].children[0],
            id: 'command-center',
            name: '命令中心',
            path: '/command/center',
            componentKey: 'command-center',
            children: []
          }
        ]
      }
    ];

    setLocale('en-US');
    const result = buildDynamicNavigation(commandNodes);
    setLocale('zh-CN');

    expect(result.routes).toHaveLength(1);
    expect(result.routes[0]?.meta?.componentFallback).toBe(false);
    expect(result.unknownComponentKeys).toEqual([]);
    expect(result.menus[0]).toMatchObject({ label: 'Command Management', i18nKey: 'mine.menu.command' });
    expect(result.menus[0]?.children?.[0]).toMatchObject({
      label: 'Command Center',
      i18nKey: 'mine.menu.commandCenter'
    });
  });
});
