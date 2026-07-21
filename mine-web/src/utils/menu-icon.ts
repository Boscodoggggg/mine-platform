export type MenuIconType = 'iconify' | 'local';

const localIconPrefix = 'local:';

export function resolveMenuIcon(value?: string | null) {
  const normalized = value?.trim();
  if (!normalized) return {};
  if (normalized.startsWith(localIconPrefix)) {
    const localIcon = normalized.slice(localIconPrefix.length).trim();
    return localIcon ? { localIcon } : {};
  }
  return { icon: normalized };
}

export function resolveMenuIconType(value?: string | null): MenuIconType {
  return value?.trim().startsWith(localIconPrefix) ? 'local' : 'iconify';
}

export function encodeMenuIcon(type: MenuIconType, value?: string | null) {
  const normalized = value?.trim();
  if (!normalized) return undefined;
  return type === 'local' ? `${localIconPrefix}${normalized}` : normalized;
}
