import type { Component } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import type { RouteKey, RoutePath } from '@elegant-router/types';
import BaseLayout from '@/layouts/base-layout/index.vue';
import { useSvgIcon } from '@/hooks/common/icon';
import { $t } from '@/locales';
import type { NavigationMenuNode } from '@/modules/system/api/auth';

type ViewLoader = () => Promise<Component>;

/**
 * 服务端 componentKey 到本地页面的唯一映射表。
 *
 * 后端配置只能选择这里声明的键，绝不能把后端字符串拼进 import 路径。
 */
const componentRegistry: Readonly<Record<string, ViewLoader>> = {
  'home-overview': () => import('@/views/home/index.vue'),
  'system-user-list': () => import('@/modules/system/pages/users/UserManagementPage.vue'),
  'system-role-list': () => import('@/modules/system/pages/roles/RoleManagementPage.vue')
};

const fallbackView: ViewLoader = () => import('./DynamicFallbackPage.vue');
const safeRoutePathPattern = /^\/[A-Za-z0-9/_-]*$/;
const navigationI18nKeyByPath: Readonly<Partial<Record<string, App.I18n.I18nKey>>> = {
  '/system': 'mine.menu.system',
  '/system/users': 'mine.menu.users',
  '/system/roles': 'mine.menu.roles',
  '/device': 'mine.menu.device',
  '/device/products': 'mine.menu.products',
  '/device/devices': 'mine.menu.devices'
};
const navigationI18nKeyByComponent: Readonly<Partial<Record<string, App.I18n.I18nKey>>> = {
  'home-overview': 'mine.menu.overview',
  'system-user-list': 'mine.menu.users',
  'system-role-list': 'mine.menu.roles'
};

export interface DynamicNavigationResult {
  routes: RouteRecordRaw[];
  menus: App.Global.Menu[];
  unknownComponentKeys: string[];
}

/** 将授权菜单树转换为本地路由与可见导航。 */
export function buildDynamicNavigation(nodes: NavigationMenuNode[]): DynamicNavigationResult {
  const routes: RouteRecordRaw[] = [];
  const unknownComponentKeys = new Set<string>();

  visitNodes(sortNodes(nodes), node => {
    if (node.type !== 'MENU' || !isSafeRoutePath(node.path)) return;

    const componentKey = node.componentKey || '';
    const component = componentRegistry[componentKey];
    const componentFallback = !component;
    if (componentFallback && componentKey) {
      unknownComponentKeys.add(componentKey);
    }

    const routeName = createRouteName('dynamic', node.id);
    const i18nKey = resolveNavigationI18nKey(node);
    routes.push({
      path: node.path,
      name: `${routeName}-layout`,
      component: BaseLayout,
      redirect: { name: routeName },
      meta: {
        title: node.name,
        i18nKey,
        icon: node.icon || undefined,
        hideInMenu: true,
        componentKey: node.componentKey,
        componentFallback
      },
      children: [
        {
          path: '',
          name: routeName,
          component: component || fallbackView,
          meta: {
            title: node.name,
            i18nKey,
            icon: node.icon || undefined,
            componentKey: node.componentKey,
            componentFallback
          }
        }
      ]
    });
  });

  return {
    routes,
    menus: buildMenus(sortNodes(nodes)),
    unknownComponentKeys: [...unknownComponentKeys]
  };
}

function buildMenus(nodes: NavigationMenuNode[]): App.Global.Menu[] {
  return nodes.flatMap(node => {
    if (!node.visible || node.type === 'BUTTON') return [];

    const children = buildMenus(sortNodes(node.children));
    if (node.type === 'DIRECTORY') {
      if (children.length === 0) return [];

      return [createMenu(node, children)];
    }
    if (!isSafeRoutePath(node.path)) return [];

    return [createMenu(node, children.length > 0 ? children : undefined)];
  });
}

function createMenu(node: NavigationMenuNode, children?: App.Global.Menu[]): App.Global.Menu {
  const { SvgIconVNode } = useSvgIcon();
  const routeName = createRouteName(node.type === 'DIRECTORY' ? 'dynamic-group' : 'dynamic', node.id);
  const i18nKey = resolveNavigationI18nKey(node);

  return {
    key: routeName,
    label: i18nKey ? $t(i18nKey) : node.name,
    i18nKey,
    routeKey: routeName as RouteKey,
    routePath: (node.path || '/') as RoutePath,
    icon: SvgIconVNode({ icon: node.icon || import.meta.env.VITE_MENU_ICON || 'mdi:menu', fontSize: 20 }),
    children
  };
}

function resolveNavigationI18nKey(node: NavigationMenuNode): App.I18n.I18nKey | undefined {
  if (node.path && navigationI18nKeyByPath[node.path]) return navigationI18nKeyByPath[node.path];
  if (node.componentKey) return navigationI18nKeyByComponent[node.componentKey];
  return undefined;
}

function visitNodes(nodes: NavigationMenuNode[], visitor: (node: NavigationMenuNode) => void) {
  nodes.forEach(node => {
    visitor(node);
    visitNodes(sortNodes(node.children), visitor);
  });
}

function sortNodes(nodes: NavigationMenuNode[]) {
  return [...nodes].sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name));
}

function isSafeRoutePath(path: string | null): path is string {
  return Boolean(path && safeRoutePathPattern.test(path) && !path.includes('//') && !path.includes('..'));
}

function createRouteName(prefix: string, id: string) {
  const safeId = id.replace(/[^A-Za-z0-9_-]/g, '-');
  return `${prefix}-${safeId}`;
}
