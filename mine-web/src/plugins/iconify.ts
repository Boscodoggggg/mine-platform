import { addCollection } from '@iconify/vue/offline';
import offlineIconCollections from 'virtual:mine-offline-icons';

/** 在应用启动时注入经过裁剪的本地 Iconify 数据。 */
export function setupIconifyOffline() {
  offlineIconCollections.forEach(collection => addCollection(collection));
}
