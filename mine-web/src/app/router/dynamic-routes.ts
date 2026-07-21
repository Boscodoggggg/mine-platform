import type { Component } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import type { RouteKey, RoutePath } from '@elegant-router/types';
import BaseLayout from '@/layouts/base-layout/index.vue';
import { useSvgIcon } from '@/hooks/common/icon';
import { resolveMenuIcon } from '@/utils/menu-icon';
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
  'system-role-list': () => import('@/modules/system/pages/roles/RoleManagementPage.vue'),
  'system-menu-list': () => import('@/modules/system/pages/menus/MenuManagementPage.vue'),
  'system-dictionary-list': () => import('@/modules/system/pages/dictionaries/DictionaryManagementPage.vue'),
  'system-operations-monitor': () => import('@/modules/system/pages/operations/SystemOperationsPage.vue'),
  'device-product-list': () => import('@/modules/device/pages/product-list/ProductListPage.vue'),
  'device-device-list': () => import('@/modules/device/pages/device-list/DeviceListPage.vue'),
  'device-model-management': () => import('@/modules/device/pages/model-management/DeviceModelManagementPage.vue'),
  'device-strategy-workbench': () => import('@/modules/device/pages/strategy/StrategyWorkbenchPage.vue'),
  'retention-governance': () => import('@/modules/device/pages/strategy/StrategyWorkbenchPage.vue'),
  'aggregation-governance': () => import('@/modules/device/pages/aggregation-governance/AggregationGovernancePage.vue'),
  'device-monitor': () => import('@/modules/monitoring/pages/device-monitor/DeviceMonitorPage.vue'),
  'command-center': () => import('@/modules/command/pages/command-center/CommandCenterPage.vue'),
  'command-templates': () => import('@/modules/command/pages/command-templates/CommandTemplatePage.vue'),
  'alarm-center': () => import('@/modules/alarm/pages/alarm-center/AlarmCenterPage.vue'),
  'notification-channel-management': () =>
    import('@/modules/notification/pages/channel-management/NotificationChannelManagementPage.vue'),
  'mine-site-archive': () => import('@/modules/mine/pages/site-archive/MineSiteArchivePage.vue'),
  'mine-vehicle-management': () => import('@/modules/mine/pages/vehicle-management/VehicleManagementPage.vue'),
  'mine-person-management': () => import('@/modules/mine/pages/person-management/PersonManagementPage.vue'),
  'mine-location-tag-management': () => import('@/modules/mine/pages/tag-management/LocationTagManagementPage.vue'),
  'mine-binding-management': () => import('@/modules/mine/pages/binding-management/BindingManagementPage.vue'),
  'mine-location-monitor': () => import('@/modules/mine/pages/realtime-location/RealtimeLocationPage.vue'),
  'mine-spatial-management': () => import('@/modules/mine/pages/spatial-management/SpatialManagementPage.vue'),
  'mine-production-operations': () => import('@/modules/mine/pages/production-operations/ProductionOperationsPage.vue'),
  'mine-scene-workbench': () => import('@/modules/scene3d/pages/SceneWorkbenchPage.vue')
};

const safeRoutePathPattern = /^\/[A-Za-z0-9/_-]*$/;
const navigationI18nKeyByPath: Readonly<Partial<Record<string, App.I18n.I18nKey>>> = {
  '/system': 'mine.menu.system',
  '/system/users': 'mine.menu.users',
  '/system/roles': 'mine.menu.roles',
  '/system/menus': 'mine.menu.menus',
  '/system/dictionaries': 'mine.menu.dictionaries',
  '/system/operations': 'mine.menu.operations',
  '/device': 'mine.menu.device',
  '/device/products': 'mine.menu.products',
  '/device/devices': 'mine.menu.devices',
  '/device/models': 'mine.menu.models',
  '/device/strategies': 'mine.menu.strategies',
  '/device/monitor': 'mine.menu.monitor',
  '/governance': 'mine.menu.governance',
  '/governance/retention': 'mine.menu.retention',
  '/governance/aggregation': 'mine.menu.aggregation',
  '/command': 'mine.menu.command',
  '/command/center': 'mine.menu.commandCenter',
  '/command/templates': 'mine.menu.commandTemplates',
  '/alarm': 'mine.menu.alarms',
  '/alarm/center': 'mine.menu.alarmCenter',
  '/alarm/channels': 'mine.menu.notificationChannels',
  '/mine': 'mine.menu.mine',
  '/mine/sites': 'mine.menu.mineSites',
  '/mine/vehicles': 'mine.menu.mineVehicles',
  '/mine/people': 'mine.menu.minePeople',
  '/mine/location-tags': 'mine.menu.mineLocationTags',
  '/mine/bindings': 'mine.menu.mineBindings',
  '/mine/location': 'mine.menu.mineLocation',
  '/mine/spatial': 'mine.menu.mineSpatial',
  '/mine/operations': 'mine.menu.mineOperations',
  '/mine/scene': 'mine.menu.mineScene'
};
const navigationI18nKeyByComponent: Readonly<Partial<Record<string, App.I18n.I18nKey>>> = {
  'home-overview': 'mine.menu.overview',
  'system-user-list': 'mine.menu.users',
  'system-role-list': 'mine.menu.roles',
  'system-menu-list': 'mine.menu.menus',
  'system-dictionary-list': 'mine.menu.dictionaries',
  'system-operations-monitor': 'mine.menu.operations',
  'device-product-list': 'mine.menu.products',
  'device-device-list': 'mine.menu.devices',
  'device-model-management': 'mine.menu.models',
  'device-strategy-workbench': 'mine.menu.strategies',
  'retention-governance': 'mine.menu.retention',
  'aggregation-governance': 'mine.menu.aggregation',
  'device-monitor': 'mine.menu.monitor',
  'command-center': 'mine.menu.commandCenter',
  'command-templates': 'mine.menu.commandTemplates',
  'alarm-center': 'mine.menu.alarmCenter',
  'notification-channel-management': 'mine.menu.notificationChannels',
  'mine-site-archive': 'mine.menu.mineSites',
  'mine-vehicle-management': 'mine.menu.mineVehicles',
  'mine-person-management': 'mine.menu.minePeople',
  'mine-location-tag-management': 'mine.menu.mineLocationTags',
  'mine-binding-management': 'mine.menu.mineBindings',
  'mine-location-monitor': 'mine.menu.mineLocation',
  'mine-spatial-management': 'mine.menu.mineSpatial',
  'mine-production-operations': 'mine.menu.mineOperations',
  'mine-scene-workbench': 'mine.menu.mineScene'
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
    if (!component) {
      if (componentKey) unknownComponentKeys.add(componentKey);
      return;
    }

    const routeName = createRouteName('dynamic', node.id);
    const i18nKey = resolveNavigationI18nKey(node);
    const routeIcon = resolveMenuIcon(node.icon);
    routes.push({
      path: node.path,
      name: `${routeName}-layout`,
      component: BaseLayout,
      redirect: { name: routeName },
      meta: {
        title: node.name,
        i18nKey,
        ...routeIcon,
        hideInMenu: true,
        componentKey: node.componentKey,
        componentFallback: false
      },
      children: [
        {
          path: '',
          name: routeName,
          component,
          props: componentKey === 'retention-governance' ? { fixedStrategyType: 'RETENTION' } : undefined,
          meta: {
            title: node.name,
            i18nKey,
            ...routeIcon,
            componentKey: node.componentKey,
            componentFallback: false
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
    if (!isSafeRoutePath(node.path) || !node.componentKey || !componentRegistry[node.componentKey]) return [];

    return [createMenu(node, children.length > 0 ? children : undefined)];
  });
}

function createMenu(node: NavigationMenuNode, children?: App.Global.Menu[]): App.Global.Menu {
  const { SvgIconVNode } = useSvgIcon();
  const routeName = createRouteName(node.type === 'DIRECTORY' ? 'dynamic-group' : 'dynamic', node.id);
  const i18nKey = resolveNavigationI18nKey(node);
  const resolvedIcon = resolveMenuIcon(node.icon);
  const icon =
    resolvedIcon.icon || resolvedIcon.localIcon ? resolvedIcon : { icon: import.meta.env.VITE_MENU_ICON || 'mdi:menu' };

  return {
    key: routeName,
    label: i18nKey ? $t(i18nKey) : node.name,
    i18nKey,
    routeKey: routeName as RouteKey,
    routePath: (node.path || '/') as RoutePath,
    icon: SvgIconVNode({ ...icon, fontSize: 20 }),
    children
  };
}

function resolveNavigationI18nKey(node: NavigationMenuNode): App.I18n.I18nKey | undefined {
  if (node.i18nKey && supportedI18nKeys.has(node.i18nKey)) {
    return node.i18nKey as App.I18n.I18nKey;
  }
  if (node.path && navigationI18nKeyByPath[node.path]) return navigationI18nKeyByPath[node.path];
  if (node.componentKey) return navigationI18nKeyByComponent[node.componentKey];
  return undefined;
}

const supportedI18nKeys = new Set<string>(
  [...Object.values(navigationI18nKeyByPath), ...Object.values(navigationI18nKeyByComponent)].filter(
    (key): key is App.I18n.I18nKey => Boolean(key)
  )
);

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
