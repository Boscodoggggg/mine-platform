import { describe, expect, it } from 'vitest';
import { resolveIconEnv } from './icon-env';
import { setupUnocss } from './unocss';
import { setupUnplugin } from './unplugin';

describe('图标构建插件环境默认值', () => {
  it('未提供本地 .env 时仍可创建插件', () => {
    const emptyEnv = {} as Env.ImportMeta;

    expect(resolveIconEnv(emptyEnv)).toEqual({
      iconPrefix: 'icon',
      localIconPrefix: 'icon-local',
      collectionName: 'local'
    });
    expect(() => setupUnocss(emptyEnv)).not.toThrow();
    expect(() => setupUnplugin(emptyEnv)).not.toThrow();
  });
});
