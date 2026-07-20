# Cesium 三维场景、模型治理与二维降级设计

## 1. 目标与边界

本切片在已经通过仓库自动化验收的矿区资产、双坐标定位、路线/围栏、空间事件和生产运营事实之上，交付一个可离线部署、可版本化发布、可实时叠加且故障可独立降级的三维场景产品。

首个完整闭环为：

```text
上传并校验 GLB 或 3D Tiles 资源包
  -> 创建场景草稿和有序图层
  -> 发布不可变场景版本并设置默认场景
  -> 浏览器只在进入三维路由后加载 Cesium
  -> 加载本地资源、矿区位置、区域、轨迹、告警和运营状态
  -> WebGL/资源/实时链路分别显示健康状态
  -> 任一三维能力失败时立即切到独立二维坐标视图
```

平台不是 CAD/BIM、地质建模或模型转换产品。首版不编辑 DWG/IFC，不生成精细矿坑，不依赖 Cesium ion，不引入云端底图，不做三维路径规划、碰撞控制或自动驾驶控制。模型制作、现场测绘、目标 GPU/终端和容量仍为 `DEFERRED` 生产放行项。

## 2. 方案选择

采用“管理 PostgreSQL 场景控制面 + 可替换资源存储 + 不可变发布版本 + Cesium 路由级懒加载 + 独立二维适配器”方案。

没有把模型直接放进数据库 `bytea`：3D Tiles 是多文件树，数据库大对象会放大备份、连接池和范围读取成本。没有让前端直接填写任意 URL：这会绕过矿区权限、内容摘要、离线可用性和 CSP 边界。没有让 Cesium 组件成为实时定位页的基础依赖：WebGL、Worker 或模型失败必须只影响三维路由。

资源存储通过 `SceneAssetStorage` 接口隔离。首个实现使用内容寻址的文件系统目录，可挂载本地卷或共享 POSIX/NFS；接口不暴露文件路径给业务服务，后续可以增加 S3 兼容适配器而不改变场景和 API。Standalone 使用持久命名卷，五节点生产必须使用共享存储或 S3 适配器并单独完成 HA/恢复验收。

## 3. 领域模型

### 3.1 SceneAsset

`SceneAsset` 是矿区内不可变三维资源元数据：

- 类型固定为 `GLB` 或 `TILES_3D`。
- 保存显示名称、原文件名、入口相对路径、SHA-256 内容摘要、总字节数、文件数、媒体类型、创建人和状态。
- 状态固定为 `AVAILABLE/ARCHIVED/QUARANTINED`；校验失败的临时文件不发布为 `AVAILABLE`。
- `SceneAssetFile` 保存包内每个安全相对路径、文件摘要、字节数和媒体类型，禁止绝对路径、`..`、软链接和大小写冲突。
- 已被发布场景版本引用的资源不能删除；归档只阻止新草稿引用，不破坏历史场景。

GLB 校验至少覆盖 `glTF` magic、版本 2、声明长度等于文件长度和全局上限。3D Tiles 使用 ZIP 上传，必须限制压缩包大小、解压后总量、单文件大小、文件数量和压缩比，拒绝 Zip Slip、软链接、重复路径、控制字符和超限 JSON；根目录必须唯一包含可解析 `tileset.json`，其内部 URI 只能指向包内安全相对文件。

### 3.2 Scene 与 SceneVersion

`Scene` 是矿区内稳定对象，保存唯一编码、名称、说明、默认标识、活动/归档状态和乐观版本。一个矿区最多一个活动默认场景，但可以保留多个生产区域场景。

`SceneVersion` 保存修订号、`DRAFT/PUBLISHED/SUPERSEDED` 状态、矿区统一坐标系、场景原点、旋转、统一缩放、默认相机、二维视口和规范化内容摘要。发布时：

1. 锁定稳定场景和草稿；
2. 校验坐标系属于同一矿区且为统一坐标系；
3. 校验所有静态资源可用、图层顺序唯一、数值有限且资源/图层类型匹配；
4. 规范化图层顺序并计算 `sha256:` 内容摘要；
5. 将上一 `PUBLISHED` 版本标记为 `SUPERSEDED`，当前草稿标记为 `PUBLISHED`；
6. 已发布版本及图层从此不可修改或删除。

场景发布不是空间事实重算。位置、区域和运营事实仍引用矿区统一坐标；场景版本只定义如何把统一坐标投影到可视化世界。

### 3.3 SceneLayer

图层属于场景版本，类型固定为：

- `STATIC_3D_TILES`：引用 `TILES_3D` 资源。
- `STATIC_GLB`：引用 `GLB` 资源，表示矿坑、建筑或固定设备。
- `GRID`：无资源的本地三维/二维网格降级层。
- `DYNAMIC_LOCATIONS`：车辆、人员和标签当前位置。
- `DYNAMIC_REGIONS`：途径点和围栏。
- `DYNAMIC_TRAJECTORY`：选中对象的有界轨迹。
- `DYNAMIC_ALARMS`：可映射到当前位置的未关闭告警。
- `DYNAMIC_OPERATIONS`：当前班次/作业和最新运营变化。

每层保存稳定层编码、名称、顺序、默认可见性、透明度、最小/最大显示距离和局部偏移/旋转/缩放。动态层保存固定的最大对象数和刷新/轨迹窗口上限；服务端和值班页面都不得接受任意脚本、着色器或远程 URL。

## 4. 坐标与显示语义

位置事实已经同时保存原始坐标和矿区统一坐标。场景运行时只使用统一坐标 `localX/localY/localZ`：

```text
矿区统一坐标
  -> 减去 SceneVersion 原点
  -> 应用统一缩放和 heading/pitch/roll
  -> 转为 Cesium local ENU 模型矩阵或二维坐标
```

场景版本保存的变换不可用于重写历史位置。三维世界锚点使用显式 WGS84 经纬高或本地笛卡尔原点配置；若现场没有可信地理锚点，使用本地 ENU/网格模式并在页面明确标识“矿区局部坐标”，不得伪装成真实全球定位。

所有坐标、缩放、距离和相机数值必须有限并设上限。高度缺失的位置使用场景默认地面高度并显示质量降级；低质量位置可以展示，但不能因为三维图标看似精确而升级其业务可信度。

## 5. 存储、事务与资源交付

资源写入流程：

```text
流式上传到隔离临时目录
  -> 大小/摘要/格式/压缩包安全校验
  -> 写内容寻址目录 {sha256}/...
  -> 数据库事务登记资源和文件清单
  -> 提交后资源可见
```

文件系统和数据库无法形成分布式事务。内容寻址写入是幂等的；数据库失败只可能留下未引用对象，由有界对账任务在宽限期后清理。绝不先登记 `AVAILABLE` 再异步校验。覆盖同一摘要不会改写已有内容。

浏览器只能通过受权 API 读取资源：

```text
GET /api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/{relativePath}
```

服务端从数据库文件清单解析存储键，不把请求路径拼接到宿主机路径。响应提供 `ETag`、`Content-Length`、白名单 `Content-Type`、`nosniff`、缓存控制和单范围 `Range`；入口和相对 Tiles URI 保持同源，适配矿区离线部署、Sa-Token 会话和 CSP。归档资源仍允许已发布版本读取。

## 6. API、权限与审计

管理 API 位于 `/api/v1/mine/sites/{siteId}`：

```text
GET/POST          /scene-assets
GET               /scene-assets/{assetId}
POST              /scene-assets/{assetId}/archive
GET               /scene-assets/{assetId}/files/{relativePath}

GET/POST          /scenes
GET/PATCH         /scenes/{sceneId}
POST              /scenes/{sceneId}/archive
POST              /scenes/{sceneId}/make-default
GET/POST          /scenes/{sceneId}/versions
GET/PATCH         /scenes/{sceneId}/versions/{versionId}
PUT               /scenes/{sceneId}/versions/{versionId}/layers
POST              /scenes/{sceneId}/versions/{versionId}/publish
GET               /scene-runtime
```

权限固定为：

- `mine:scene:view`：菜单、运行快照和资源读取。
- `mine:scene:edit`：场景、草稿和图层编辑。
- `mine:scene:asset:upload`：资源上传和归档。
- `mine:scene:publish`：发布和切换默认场景。

全部接口同时校验组织/矿区范围。上传、归档、发布、默认切换写操作写系统审计；发布和默认切换使用乐观版本及稳定 `Idempotency-Key`。资源下载日志不逐块写业务审计，使用低基数指标和访问日志，避免 Range 请求造成审计风暴。

## 7. 运行快照与兼容性

`GET /scene-runtime` 返回默认场景的最后正确已发布版本、内容摘要、坐标锚点、默认相机、二维视口、有序图层和受权同源资源入口。没有默认场景、默认场景未发布或存储暂不可用时返回结构化降级原因，而不是返回半包。

前端只接受完整且 schema 版本匹配的快照。新字段向后兼容增加；删除图层类型、改变坐标语义或资源入口需要升级 schema。编辑草稿失败不影响最后正确发布版本。

## 8. 前端架构与独立降级

动态菜单组件键固定为 `mine-scene-workbench`，路径 `/mine/scene`。页面分为“运行场景”和“场景治理”两个页签；只读用户看运行场景，拥有编辑权限的用户管理资源、草稿和发布。

`SceneViewport` 是小型宿主，不静态导入 Cesium：

```text
mode=AUTO/3D 且 WebGL 可用
  -> dynamic import CesiumSceneViewport
  -> 成功：3D_READY
  -> Worker/资源/初始化失败：3D_DEGRADED
  -> CanvasSceneViewport（不依赖 Cesium）

mode=2D 或 WebGL 不可用
  -> 直接 CanvasSceneViewport
```

二维视图复用现有 `MineCoordinateCanvas` 和相同位置/区域/事件数据，但不从 Cesium 组件导入类型或状态。用户可以显式切换 2D/3D；自动降级保留最后数据、显示原因和重试入口，不清空为零。场景治理 API、其他矿区管理页面和 RealtimeHub 在 Cesium chunk 失败时仍可工作。

Cesium 固定 1.141，Worker/Assets/Widgets/ThirdParty 静态资源复制进离线前端包，设置本地 `CESIUM_BASE_URL`，默认关闭 ion token、远程影像和在线地形。生产构建把 Cesium 放入独立异步 chunk；非三维路由的入口 chunk 不得依赖 `cesium`。

## 9. 实时叠加与性能

页面复用现有 REST 快照后接 WebSocket 模式：

- `MINE_SITE_LOCATION`：当前位置批量增量，序号缺口回查位置快照。
- `MINE_SITE_SPATIAL_EVENT`：区域事件提示，独立失败不影响位置。
- `MINE_SITE_OPERATION`：班次/作业/记录变更，只触发有界运营回查。
- 告警通过分页 REST 首次加载和现有告警实时/刷新边界映射到具有当前位置的设备。

高频位置不进入 Pinia，也不为每帧重建 Entity。Cesium 适配器维护按对象 ID 的 `PointPrimitive/Model` 池，在动画帧内合并更新，启用 `requestRenderMode`，限制一次可见对象和轨迹点，视距外隐藏或降级为点。选择对象后才加载有界轨迹；区域几何版本变化才重建 Primitive。页面销毁时释放订阅、Primitive、Tileset、Model 和 Viewer。

## 10. 安全、可观测性与恢复

上传和读取必须防御路径穿越、压缩炸弹、超大 JSON、MIME 欺骗、跨矿引用、任意外链、脚本资源和缓存投毒。GLB/3D Tiles 只作为数据加载，不允许页面执行包内 HTML/JavaScript。CSP 的 Worker、blob 和同源资源范围必须最小化并经过构建测试。

后端暴露：

- 存储健康和最后检查结果；
- 上传/校验/发布/资源读取的固定 `stage/result/reason/assetType` 计数；
- 活动资源数、隔离资源数、读取失败率和无默认发布场景矿区数；
- 禁止把组织、矿区、场景、资源、文件路径、用户和异常文本放入指标标签。

告警至少覆盖存储不可用、资源读取失败率、隔离资源突增和默认场景快照失败。三维故障首先降级到二维；资源修复使用相同摘要对象或发布更高场景版本，禁止修改已发布版本、替换同一摘要内容或直接改数据库存储键。

## 11. 自动化验收

自动化必须证明：

- 数据库表/字段中文注释完整，跨矿、状态、不可变性和唯一默认场景约束有效。
- GLB 和 3D Tiles 包正常通过；Zip Slip、软链接、重复路径、压缩炸弹、超限和错误 URI 被拒绝且不留下 `AVAILABLE` 元数据。
- 文件读取只允许清单内路径，支持 ETag/Range/缓存头，归档历史资源仍可由发布场景读取。
- 发布摘要确定、上一版本被替代、失败继续使用最后正确版本、跨矿坐标/资产引用被拒绝。
- 非三维路由不加载 Cesium chunk；WebGL/Worker/资源失败时二维视图可用且管理 API 不受影响。
- 位置、区域、事件、告警和运营增量按各自水位恢复；对象池不重复、卸载释放资源、轨迹有界。
- 1366×768、1920×1080 和大屏关键交互可用；亮/暗/跟随系统与中英文门禁通过。
- Compose、离线静态资源、指标、Prometheus 规则和运行手册通过契约测试。

## 12. 生产放行前延期项

- 客户 GLB/3D Tiles/DWG 转换成果、授权、坐标原点、旋转、缩放和 LOD 验收。
- 现场 GPU/浏览器/大屏终端清单、驱动、WebGL 能力和目标对象/图层/瓦片容量基线。
- 共享文件系统或 S3 适配器的多节点一致性、备份恢复、故障切换和带宽/缓存验证。
- 真实车辆/人员模型、告警定位、遮挡/高度、地形贴合和二维/三维误差验收。
- 弱网、断点读取、浏览器崩溃恢复和连续 24/72 小时长稳。

这些项目保持 `DEFERRED`，不阻止仓库功能开发，但阻止“任意矿区三维生产放行”的对外声明。
