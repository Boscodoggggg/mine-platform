import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, resolve } from 'node:path';
import type { Plugin } from 'vite';

const require = createRequire(import.meta.url);

export const CESIUM_STATIC_DIRECTORIES = ['Workers', 'Assets', 'Widgets', 'ThirdParty'] as const;

function resolveCesiumBuildRoot(): string {
  const packageEntry = require.resolve('cesium');
  return resolve(dirname(packageEntry), 'Build', 'Cesium');
}

export function cesiumManualChunk(moduleId: string): string | undefined {
  const normalizedId = moduleId.replaceAll('\\', '/');
  if (
    normalizedId.includes('/node_modules/cesium/') ||
    normalizedId.includes('/node_modules/@cesium/')
  ) {
    return 'cesium';
  }
  return undefined;
}

export function setupCesiumOfflinePlugin(): Plugin {
  const sourceRoot = resolveCesiumBuildRoot();
  let outputRoot = '';

  return {
    name: 'mine-cesium-offline-assets',
    apply: 'build',
    configResolved(config) {
      outputRoot = isAbsolute(config.build.outDir)
        ? config.build.outDir
        : resolve(config.root, config.build.outDir);
    },
    buildStart() {
      for (const directory of CESIUM_STATIC_DIRECTORIES) {
        const source = resolve(sourceRoot, directory);
        if (!existsSync(source)) {
          this.error(`Cesium 离线静态资源缺失：${directory}`);
        }
      }
      this.emitFile({
        type: 'chunk',
        id: 'cesium',
        name: 'cesium',
        preserveSignature: 'strict'
      });
    },
    writeBundle() {
      const cesiumOutputRoot = resolve(outputRoot, 'cesium');
      mkdirSync(cesiumOutputRoot, { recursive: true });
      for (const directory of CESIUM_STATIC_DIRECTORIES) {
        cpSync(resolve(sourceRoot, directory), resolve(cesiumOutputRoot, directory), {
          recursive: true
        });
      }
    }
  };
}
