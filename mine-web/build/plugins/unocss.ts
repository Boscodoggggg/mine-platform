import process from 'node:process';
import path from 'node:path';
import { presetIcons } from 'unocss';
import unocss from 'unocss/vite';
import { FileSystemIconLoader } from '@iconify/utils/lib/loader/node-loaders';
import { resolveIconEnv } from './icon-env';

export function setupUnocss(viteEnv: Env.ImportMeta) {
  const { iconPrefix, collectionName } = resolveIconEnv(viteEnv);

  const localIconPath = path.join(process.cwd(), 'src/assets/svg-icon');

  return unocss({
    presets: [
      presetIcons({
        prefix: `${iconPrefix}-`,
        scale: 1,
        extraProperties: {
          display: 'inline-block'
        },
        collections: {
          [collectionName]: FileSystemIconLoader(localIconPath, svg =>
            svg.replace(/^<svg\s/, '<svg width="1em" height="1em" ')
          )
        },
        warn: true
      })
    ]
  });
}
