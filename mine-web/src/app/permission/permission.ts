import type { App, DirectiveBinding } from 'vue';
import { useAuthStore } from '@/store/modules/auth';

export type PermissionRequirement = string | string[] | { all: string[] };

const originalHiddenState = new WeakMap<HTMLElement, boolean>();

/** 判断已授予权限是否满足单个、任一或全部权限要求。 */
export function hasPermission(granted: readonly string[], requirement: PermissionRequirement): boolean {
  if (granted.includes('*')) return true;

  const permissionSet = new Set(granted);
  if (typeof requirement === 'string') {
    return Boolean(requirement) && permissionSet.has(requirement);
  }
  if (Array.isArray(requirement)) {
    return requirement.some(permission => permissionSet.has(permission));
  }
  return requirement.all.length > 0 && requirement.all.every(permission => permissionSet.has(permission));
}

/** 注册 v-permission 按钮权限指令。 */
export function setupPermissionDirective(app: App) {
  app.directive<HTMLElement, PermissionRequirement>('permission', {
    mounted: syncPermissionVisibility,
    updated: syncPermissionVisibility,
    beforeUnmount(el) {
      originalHiddenState.delete(el);
    }
  });
}

function syncPermissionVisibility(el: HTMLElement, binding: DirectiveBinding<PermissionRequirement>) {
  if (!originalHiddenState.has(el)) {
    originalHiddenState.set(el, Boolean(el.hidden));
  }

  const authStore = useAuthStore();
  const allowed = hasPermission(authStore.userInfo.buttons, binding.value);
  el.hidden = Boolean(originalHiddenState.get(el)) || !allowed;

  if (allowed) {
    el.removeAttribute('aria-hidden');
  } else {
    el.setAttribute('aria-hidden', 'true');
  }
}
