import { menuIconifyOptions } from '../modules/system/components/menu-icon-options';

/** 需要在内网产物中预置的运行时图标。 */
const runtimeIconNames = [
  'ant-design:bar-chart-outlined',
  'ant-design:close-outlined',
  'ant-design:column-width-outlined',
  'ant-design:line-outlined',
  'ant-design:money-collect-outlined',
  'ant-design:trademark-circle-outlined',
  'carbon:checkmark-outline',
  'carbon:chevron-right',
  'carbon:code',
  'carbon:data-structured',
  'carbon:devices',
  'carbon:document-download',
  'carbon:play-outline',
  'carbon:unknown',
  'carbon:warning-alt',
  'heroicons:language',
  'line-md:menu-fold-left',
  'line-md:menu-fold-right',
  'majesticons:color-swatch-line',
  'material-symbols:hdr-auto',
  'material-symbols:lock-outline',
  'material-symbols:login',
  'material-symbols:nightlight-rounded',
  'material-symbols:person-outline',
  'material-symbols:sunny',
  'mdi:format-horizontal-align-left',
  'mdi:format-horizontal-align-right',
  'mdi:help-circle',
  'mdi:pin',
  'mdi:pin-off',
  'mdi:pin-off-outline',
  'mdi:pin-outline',
  'ph:caret-double-left-bold',
  'ph:caret-double-right-bold',
  'ph:sign-out',
  'ph:user-circle'
] as const;

/** 数据库迁移已发布、但不一定出现在菜单编辑器候选项中的图标。 */
const seededMenuIconNames = [
  'mdi:account-hard-hat-outline',
  'mdi:alarm-light-outline',
  'mdi:alarm-panel-outline',
  'mdi:chart-timeline-variant-shimmer',
  'mdi:clipboard-flow-outline',
  'mdi:crosshairs-gps',
  'mdi:database-clock-outline',
  'mdi:database-cog-outline',
  'mdi:dump-truck',
  'mdi:map-marker-path',
  'mdi:map-marker-radius-outline',
  'mdi:message-alert-outline',
  'mdi:mine',
  'mdi:terrain',
  'mdi:vector-polyline'
] as const;

export const offlineIconNames = [
  ...new Set<string>([...menuIconifyOptions, ...runtimeIconNames, ...seededMenuIconNames])
] as const;

const offlineIconNameSet = new Set<string>(offlineIconNames);

/** 判断图标是否已收录到内网离线包。 */
export function isOfflineIconName(iconName: string) {
  return offlineIconNameSet.has(iconName);
}

/** 兼容历史组件使用的 `mdi-name` 和 `ph-name` 写法。 */
export function normalizeOfflineIconName(iconName: string) {
  const normalized = iconName.trim();
  if (!normalized || normalized.includes(':')) return normalized;
  const separator = normalized.indexOf('-');
  return separator > 0 ? `${normalized.slice(0, separator)}:${normalized.slice(separator + 1)}` : normalized;
}
