# mine-web

智慧矿山数据采集与设备管理平台的 Vue 3 管理端。工程以 SoybeanAdmin v2.2.0 为基础裁剪，业务页面、接口契约和权限模型由本项目自研。

## 本地命令

```bash
pnpm install --frozen-lockfile
pnpm typecheck
pnpm lint
pnpm test
pnpm build
```

开发服务器默认把 `/api` 代理到 mine-server。生产环境只发布构建产物，不依赖外网资源。
