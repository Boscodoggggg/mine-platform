import { readFileSync, readdirSync, statSync } from 'node:fs';
import { relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import packageJson from '../../package.json';
import {
  CESIUM_STATIC_DIRECTORIES,
  cesiumManualChunk
} from '../../build/plugins/cesium';

const projectRoot = resolve(import.meta.dirname, '../..');
const sourceRoot = resolve(projectRoot, 'src');
const loaderPath = 'src/modules/scene3d/runtime/load-cesium.ts';

function productionSourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap(name => {
    const absolutePath = resolve(directory, name);
    if (statSync(absolutePath).isDirectory()) {
      return productionSourceFiles(absolutePath);
    }
    if (!/\.(?:ts|tsx|vue|scss|css)$/.test(name) || name.includes('.test.')) {
      return [];
    }
    return [absolutePath];
  });
}

function importsCesiumRoot(source: string): boolean {
  return [
    "from 'cesium'",
    'from "cesium"',
    "import('cesium')",
    'import("cesium")'
  ].some(pattern => source.includes(pattern));
}

describe('Cesium bundle boundary', () => {
  it('固定 Cesium 版本并声明完整离线静态目录', () => {
    expect(packageJson.dependencies.cesium).toBe('1.141.0');
    expect(CESIUM_STATIC_DIRECTORIES).toEqual([
      'Workers',
      'Assets',
      'Widgets',
      'ThirdParty'
    ]);
  });

  it('只有场景运行加载器可以导入 Cesium 根包', () => {
    const imports = productionSourceFiles(sourceRoot)
      .filter(file => importsCesiumRoot(readFileSync(file, 'utf8')))
      .map(file => relative(projectRoot, file));

    expect(imports).toEqual([loaderPath]);
  });

  it('Cesium 与其官方子包进入独立异步 chunk', () => {
    expect(cesiumManualChunk('/workspace/node_modules/cesium/Source/Cesium.js')).toBe('cesium');
    expect(cesiumManualChunk('/workspace/node_modules/@cesium/engine/index.js')).toBe('cesium');
    expect(cesiumManualChunk('/workspace/node_modules/vue/dist/vue.runtime.esm.js')).toBeUndefined();

    const viteConfig = readFileSync(resolve(projectRoot, 'vite.config.ts'), 'utf8');
    const pluginIndex = readFileSync(resolve(projectRoot, 'build/plugins/index.ts'), 'utf8');
    const offlinePlugin = readFileSync(resolve(projectRoot, 'build/plugins/cesium.ts'), 'utf8');
    expect(viteConfig).toContain('manualChunks: cesiumManualChunk');
    expect(pluginIndex).toContain('setupCesiumOfflinePlugin()');
    expect(offlinePlugin).toContain("id: 'cesium'");
    expect(offlinePlugin).toContain('cpSync(resolve(sourceRoot, directory)');
  });

  it('全局样式不预加载 Cesium Widgets CSS', () => {
    const globalStyles = productionSourceFiles(resolve(sourceRoot, 'styles'))
      .map(file => readFileSync(file, 'utf8'))
      .join('\n');

    expect(globalStyles).not.toContain('cesium/Build/Cesium/Widgets/widgets.css');
  });
});
