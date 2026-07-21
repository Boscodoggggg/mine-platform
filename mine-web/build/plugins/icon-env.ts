export interface ResolvedIconEnv {
  iconPrefix: string;
  localIconPrefix: string;
  collectionName: string;
}

export function resolveIconEnv(viteEnv: Env.ImportMeta): ResolvedIconEnv {
  const iconPrefix = viteEnv.VITE_ICON_PREFIX || 'icon';
  const localIconPrefix = viteEnv.VITE_ICON_LOCAL_PREFIX || `${iconPrefix}-local`;
  const collectionName = localIconPrefix.replace(`${iconPrefix}-`, '');

  return { iconPrefix, localIconPrefix, collectionName };
}
