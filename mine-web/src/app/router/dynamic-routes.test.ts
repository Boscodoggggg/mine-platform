import { describe, expect, it } from 'vitest';
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
});
