# 确定性矿区三维演示资源

`demo-mine-grid.glb` 是仓库脚本离线生成的最小 GLB 2.0 资源，只包含自制的矿区坐标网格、分层采坑轮廓和运输道路线框，不下载或混入任何第三方模型。

固定契约：

- 生成器：`scripts/generate-demo-scene-assets.mjs`
- 坐标约定：局部 ENU，单位为米
- 许可：CC0-1.0
- SHA-256：`4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78`

在仓库根目录校验或重新生成：

```bash
node scripts/generate-demo-scene-assets.mjs --check
node scripts/generate-demo-scene-assets.mjs
```

生成器固定 JSON 字段顺序、浮点小端序、GLB 块对齐和元数据。`--check` 会同时比较重新生成的字节、仓库文件和固定摘要；任何几何或元数据变化都必须显式更新生成器、摘要和本说明。

## 导入场景工作台

1. 使用具备 `mine:scene:asset:upload` 权限的账号进入“矿区管理 / 三维场景”。
2. 在“场景治理”中选择目标矿区，上传 `demo-mine-grid.glb`，资源类型选择 GLB。
3. 创建或编辑草稿版本，增加静态 GLB 图层并设置该资源为入口。
4. 校验坐标原点和局部 ENU 变换后发布版本，再将稳定场景设为当前矿区默认场景。
5. 在“运行场景”检查三维线框、二维降级、位置和业务图层；资源读取失败只应降级对应静态图层。

Standalone 使用 `scene_upload_workspace` 命名卷分别承载 Servlet multipart 暂存和 S3 上传前的受控本地工作区，最终对象保存在 RustFS 的 `rustfs_data` 卷。部署前必须为两个卷预留满足上传与展开上限的磁盘空间，不能让大文件上传回落到公共的 256 MiB `/tmp`。

该资源用于开发、演示和自动化回归，不代表真实矿体、测绘地形、生产道路或坐标标定结果，也不能替代目标 GPU、浏览器、共享存储和现场数据验证。
