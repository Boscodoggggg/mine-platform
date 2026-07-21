import { getIcons } from '@iconify/utils';
import { loadCollectionFromFS } from '@iconify/utils/lib/loader/fs';
import type { Plugin } from 'vite';
import { offlineIconNames } from '../../src/plugins/offline-icon-names';

const moduleId = 'virtual:mine-offline-icons';
const resolvedModuleId = `\0${moduleId}`;

/** 将已知运行时图标裁剪为内网可用的 Iconify 集合。 */
export function setupOfflineIcons(): Plugin {
  return {
    name: 'mine:offline-icons',
    enforce: 'pre',
    resolveId(id) {
      return id === moduleId ? resolvedModuleId : null;
    },
    async load(id) {
      if (id !== resolvedModuleId) return null;

      const namesByPrefix = new Map<string, string[]>();
      offlineIconNames.forEach(iconName => {
        const separator = iconName.indexOf(':');
        if (separator <= 0 || separator === iconName.length - 1) {
          throw new Error(`离线图标名称不合法: ${iconName}`);
        }
        const prefix = iconName.slice(0, separator);
        const name = iconName.slice(separator + 1);
        namesByPrefix.set(prefix, [...(namesByPrefix.get(prefix) || []), name]);
      });

      const collections = [];
      for (const [prefix, names] of namesByPrefix) {
        const source = await loadCollectionFromFS(prefix, false, undefined, process.cwd());
        if (!source) throw new Error(`未找到 Iconify 图标集: ${prefix}`);
        const collection = getIcons(source, names, true);
        const missing = collection?.not_found ?? [];
        if (!collection || missing.length > 0) {
          throw new Error(`Iconify 图标不存在: ${missing.map(name => `${prefix}:${name}`).join(', ')}`);
        }
        Reflect.deleteProperty(collection, 'not_found');
        collections.push(collection);
      }

      return `export default ${JSON.stringify(collections)};`;
    }
  };
}
