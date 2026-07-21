# 矿区三维场景运行与安全回退手册

## 1. 适用范围

本手册用于运行和排查 Phase 3 三维场景闭环：GLB/3D Tiles 资源上传、内容寻址存储、场景草稿与不可变发布、默认场景运行快照、Cesium 离线加载、二维独立降级、实时位置/空间事件/告警/生产运营叠加，以及存储告警和安全恢复。

仓库自动化已经覆盖确定性演示资源和浏览器降级行为，但不替代真实矿坑模型、现场坐标、目标 GPU/浏览器、共享 S3、容量、高可用、备份恢复、弱网和 24/72 小时长稳验证。生产放行边界见
[Phase 3 三维场景自动化验收报告](../reports/phase-3-scene-acceptance.md)。

## 2. 运行链路与不变量

~~~text
GLB 或 3D Tiles ZIP
→ 上传大小、路径、媒体类型、GLB/tileset 和外链白名单校验
→ SHA-256 内容寻址对象 + 数据库文件清单
→ 稳定场景 + DRAFT 版本 + 有序图层
→ 摘要复核并发布不可变 PUBLISHED 版本
→ 设置矿区唯一默认场景
→ 完整运行快照和受权同源资源 URL
→ 路由级懒加载 Cesium、Workers 和离线静态资源
→ 位置/轨迹/区域/空间事件/告警/生产运营独立叠加
→ WebGL、资源或实时子链路故障时局部降级或切换二维
~~~

必须始终满足：

1. 数据库发布版本和资源文件清单是事实，浏览器模式切换不能修改业务事实。
2. 资源对象以 `sha256:` 摘要寻址；相同摘要下的内容不可替换、覆盖或手工修补。
3. 已发布场景版本、图层和内容摘要不可原地修改；变更必须从已发布版本复制新草稿再发布。
4. 资源读取先校验用户权限和矿区数据范围，再按数据库清单解析相对路径；客户端不得提供对象存储键、宿主机路径或任意外链。
5. 三维存储不属于平台二维就绪门禁。三维不可用时，当前位置、轨迹、区域、空间事件、告警和生产运营仍按各自 REST/实时契约运行。
6. 任何单一静态图层加载失败只降级该图层；不能清空其他图层，也不能把最后事实伪造成零或正常。
7. 指标标签只允许固定 `stage/result/reason/assetType`，禁止加入组织、矿区、场景、资源、文件路径、用户或异常文本。

## 3. 启用前检查

### 3.1 上传和暂存边界

应用默认边界如下；生产值必须按正式模型包和主机容量复核后显式固化：

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `MINE_SCENE_MULTIPART_MAX_FILE_SIZE` | `2GB` | Servlet 单文件接收上限 |
| `MINE_SCENE_MULTIPART_MAX_REQUEST_SIZE` | `2050MB` | Multipart 请求总上限 |
| `MINE_SCENE_STORAGE_MAX_UPLOAD_BYTES` | `2147483648` | 业务流式上传上限 |
| `MINE_SCENE_STORAGE_MAX_EXPANDED_BYTES` | `8589934592` | ZIP 展开总量上限 |
| `MINE_SCENE_STORAGE_MAX_SINGLE_FILE_BYTES` | `1073741824` | 包内单文件上限 |
| `MINE_SCENE_STORAGE_MAX_FILE_COUNT` | `10000` | 包内文件数上限 |
| `MINE_SCENE_STORAGE_MAX_COMPRESSION_RATIO` | `100` | 最大展开压缩比 |
| `MINE_SCENE_STORAGE_MAX_JSON_BYTES` | `16777216` | 单个 JSON 上限 |
| `MINE_SCENE_STORAGE_ORPHAN_GRACE_PERIOD` | `PT24H` | 孤儿对象对账最小宽限期 |

Standalone 使用只读应用容器和独立 `scene_upload_workspace` 暂存卷，初始化容器把目录创建为 UID/GID `10001` 可写且权限 `0700`。确认：

```bash
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml config --quiet
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml ps --all mine-scene-staging-init mine-server rustfs
```

不得把 `MINE_SCENE_STORAGE_ROOT` 指向 Web 根目录、共享临时目录或其他服务数据目录。上传限制必须同时满足反向代理、Multipart 和业务存储三层配置，不能只提高其中一层。

### 3.2 对象存储

Standalone 固定使用标准 S3 适配器：

~~~text
MINE_SCENE_STORAGE_TYPE=s3
MINE_OBJECT_STORAGE_TYPE=s3
MINE_OBJECT_STORAGE_S3_ENDPOINT=http://rustfs:9000
MINE_OBJECT_STORAGE_S3_BUCKET=mine-scene-assets
MINE_OBJECT_STORAGE_S3_PATH_STYLE_ACCESS=true
~~~

`MINE_OBJECT_STORAGE_S3_INITIALIZE_BUCKET=true` 仅用于开发和自动化验收。生产 bucket 必须由基础设施流程预创建，使用独立最小权限凭据并关闭自动初始化；凭据不得进入 Git、日志、浏览器或场景快照。正式环境还要启用服务端加密、版本控制/对象锁策略和主机外备份，具体能力按选定 S3 产品验证。

三维存储健康组件在应用就绪后立即执行一次专用小对象创建、读取、删除探针，此后每 30 秒刷新 `mine_server_scene_storage_available`；单轮最多等待 2 秒。探针失败返回固定脱敏原因，不泄露 endpoint、bucket 或凭据。该组件不加入二维 readiness 组。

### 3.3 权限和产品入口

菜单固定为“三维场景”，路径 `/mine/scene`，前端组件白名单键 `mine-scene-workbench`。最小权限：

- `mine:scene:view`：查看场景、运行快照和受权资源文件。
- `mine:scene:edit`：创建/修改稳定场景、草稿和图层，归档非默认场景。
- `mine:scene:asset:upload`：上传和归档资源。
- `mine:scene:publish`：发布版本和切换默认场景。

页面按钮权限只改善体验；后端仍同时校验 Sa-Token 权限、组织和矿区数据范围。

## 4. 标准发布流程

### 4.1 上传资源

1. 仅上传单个 `.glb`，或入口为 `tileset.json` 的 `.zip` 3D Tiles 包。
2. 上传前用离线工具检查模型坐标、单位、纹理、文件数、展开大小和许可证；不要把未知脚本、绝对路径或网络 URL 放入包中。
3. 上传后记录资源 ID、`contentDigest`、入口文件、文件数和大小；状态只有完成数据库清单后才能变为 `AVAILABLE`。
4. 使用详情接口抽查入口和依赖文件清单。资源响应支持私有不可变缓存、`ETag/If-None-Match` 和单一 `Range`；多范围或越界范围会拒绝。

同一矿区重复上传相同摘要返回已有资源，不生成第二份业务事实。归档只结束后续治理使用；已被发布版本引用的内容仍保持受权可读，不能用归档伪装物理删除。

### 4.2 创建、发布和切换默认场景

1. 创建矿区内编码稳定的场景；编码创建后不用于承载修订语义。
2. 创建首个草稿，选择统一坐标系和 `LOCAL_ENU` 或 `WGS84_ANCHOR`，配置原点、旋转、缩放、默认相机和二维视口。
3. 原子替换草稿全部有序图层。静态层引用同矿区可用资源，动态层配置固定对象上限。
4. 复核完整内容后，以当前乐观版本和稳定 `Idempotency-Key` 发布。发布会规范化场景定义、计算 `sha256:` 内容摘要并永久锁定该版本。
5. 产品接口允许活动但未发布的场景成为默认，此时运行快照固定返回 `DEFAULT_SCENE_UNPUBLISHED`；标准运维流程只应把已经复核并发布的活动场景设为默认。切换默认必须携带当前乐观版本和稳定幂等键。
6. 修改时从当前发布版本使用 `next-draft` 完整复制，形成更高修订；禁止修改数据库中的已发布行或图层。

主要接口：

~~~text
POST      /api/v1/mine/sites/{siteId}/scene-assets
GET       /api/v1/mine/sites/{siteId}/scene-assets
GET       /api/v1/mine/sites/{siteId}/scene-assets/{assetId}
POST      /api/v1/mine/sites/{siteId}/scene-assets/{assetId}/archive
GET       /api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/{relativePath}

GET/POST  /api/v1/mine/sites/{siteId}/scenes
GET/PATCH /api/v1/mine/sites/{siteId}/scenes/{sceneId}
POST      /api/v1/mine/sites/{siteId}/scenes/{sceneId}/archive
POST      /api/v1/mine/sites/{siteId}/scenes/{sceneId}/make-default
GET/POST  /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions
POST      /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/next-draft
GET/PATCH /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}
PUT       /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers
POST      /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/publish
GET       /api/v1/mine/sites/{siteId}/scene-runtime
~~~

`scene-runtime` 只返回完整 `READY` 快照，或无半包的 `DEGRADED`：`NO_DEFAULT_SCENE`、`DEFAULT_SCENE_UNPUBLISHED`、`UNSUPPORTED_SCHEMA_VERSION`、`SCENE_STORAGE_UNAVAILABLE`、`INVALID_PUBLISHED_SNAPSHOT`。

## 5. 确定性演示与二维降级

仓库演示资源由 `scripts/generate-demo-scene-assets.mjs` 从自制线框几何确定性生成，文件为
`deploy/demo/scene/demo-mine-grid.glb`，许可为 CC0-1.0，固定 SHA-256：

~~~text
4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78
~~~

生成和校验：

```bash
node scripts/generate-demo-scene-assets.mjs
node scripts/generate-demo-scene-assets.mjs --check
```

在非生产矿区上传该 GLB，建立 `STATIC_GLB` 图层、发布并设为默认后，进入“三维场景”。页面支持：

- `自动`：WebGL 和快照可用时显示三维，否则切换二维。
- `三维`：显式尝试 Cesium；资源失败只标记对应图层降级。
- `二维`：不创建 Cesium Viewer，继续展示位置、轨迹、区域、空间事件、告警和生产事实。

Cesium、Workers、Assets、Widgets 和 ThirdParty 只在进入三维路由后从本地构建产物加载，不依赖 Cesium ion。非三维页面出现 Cesium chunk 或静态资源请求属于回归。

演示资源只能证明仓库渲染链路，不代表真实模型精度、现场坐标或商业模型许可证已通过。

## 6. 日常核查

### 6.1 浏览器和链路

1. 浏览器网络面板中资源 URL 必须是同源 `/api/v1/mine/sites/.../scene-assets/.../files/...`，不得出现任意公网模型、Cesium ion token 或对象存储直链。
2. 检查 Cesium Workers/Assets/Widgets/ThirdParty 均返回 2xx，资源入口支持 200/206/304；401/403 表示会话或权限，404 表示清单/路径，416 表示 Range 不合法，5xx 表示服务或存储故障。
3. 检查运行页分别显示位置、区域、事件、轨迹、告警和运营链路状态及最后更新时间；一个子链路降级不应清空其他事实。
4. 位置连接序号缺口通过 WebSocket `RESYNC` 获取新的 `SNAPSHOT`；空间事件和运营业务序号缺口通过各自固定水位 REST 有界回查。浏览器不得自己补造位置、告警或运营状态。

### 6.2 数据库只读核查

```sql
SELECT status, asset_type, COUNT(*)
FROM mine_scene_asset
GROUP BY status, asset_type
ORDER BY status, asset_type;

SELECT scene.id, scene.scene_code, scene.default_scene, scene.status,
       version.id AS version_id, version.revision_number,
       version.status AS version_status, version.content_hash,
       COUNT(layer.id) AS layer_count
FROM mine_scene scene
LEFT JOIN mine_scene_version version
  ON version.scene_id = scene.id AND version.status = 'PUBLISHED'
LEFT JOIN mine_scene_layer layer ON layer.scene_version_id = version.id
WHERE scene.site_id = :site_id
GROUP BY scene.id, scene.scene_code, scene.default_scene, scene.status,
         version.id, version.revision_number, version.status, version.content_hash
ORDER BY scene.default_scene DESC, scene.scene_code;
```

查询只用于核查。禁止直接更新资源、文件清单、场景、版本、图层或幂等账本。

## 7. 指标与告警

| 指标 | 作用 |
|---|---|
| `mine_server_scene_storage_available` | 专用创建/读取/删除探针：1 可用、0 不可用、NaN 尚无结果 |
| `mine_server_scene_operations_total` | 上传、校验、发布、读取、快照的固定结果计数 |
| `mine_server_scene_assets_quarantined` | 当前数据库中 `QUARANTINED` 资源数量；数据库不可读时为 NaN |

默认告警：

- `MineSceneStorageUnavailable`：专用存储探针为 0 持续 2 分钟。
- `MineSceneReadFailureRateHigh`：10 分钟读取样本不少于 100，失败率超过 5% 且持续 5 分钟。
- `MineSceneQuarantinedAssetsHigh`：隔离资源超过 50 个并持续 10 分钟。
- `MineSceneSnapshotFailuresPersistent`：10 分钟至少 3 次快照降级并持续 5 分钟。

告警出现时先保持或切换二维，再排查存储、网络、清单和发布摘要。禁止为消除告警修改摘要、伪造 `AVAILABLE/PUBLISHED` 或清空资源卷。

## 8. 内容寻址对象对账

上传成功写入对象存储、但数据库事务未完成时，可能留下没有 `mine_scene_asset` 登记的内容对象。当前版本不会自动删除这类对象；`MINE_SCENE_STORAGE_ORPHAN_GRACE_PERIOD` 是运维对账的最小保护窗口，不是后台清理承诺。

安全对账流程：

1. 暂停新资源上传，记录窗口、bucket 版本和数据库快照时点。
2. 只读导出数据库全部 `content_digest` 及其文件 `relative_path/content_digest/size_bytes`。
3. 使用所选 S3 产品的受控运维工具只读列出 `scene/{64位摘要}/{安全相对路径}` 对象、大小、ETag 和最后修改时间；忽略 `scene/.health/` 临时探针。当前对象存储端口不提供清单 API，也不写入 SHA-256 自定义元数据，不能把 ETag 当作内容摘要。
4. 对数据库已登记对象逐项比对路径和大小，并在受控环境流式读取计算 SHA-256，与 `mine_scene_asset_file.content_digest` 比对；对象缺失或内容不符属于数据损坏，立即停止清理并进入恢复流程。
5. 只有对象存在、数据库全局完全无该摘要、最后修改时间超过宽限期且期间无进行中上传时，才能列为“候选孤儿”；宽限期和候选身份都不是可删除证明。
6. 为候选对象保存键、大小、最后修改时间、ETag 和可选流式 SHA-256 审计清单。对于 3D Tiles，前缀是已经丢失的原始 ZIP 上传摘要，不能从展开后的对象集合反推验证；候选对象又没有数据库文件清单可作为逐对象摘要权威值。
7. 当前版本到此停止，只生成候选报告，不执行删除。待平台提供持久上传清单/对象 SHA-256 元数据和受测的受审计清理器，或存在能够重建并验证完整文件清单的外部不可变交付证据后，再单独设计、测试和审批删除流程。恢复上传并保留全部导出清单、操作者和时间。

禁止用 `docker volume rm`、`docker compose down -v`、bucket 清空或前缀递归删除代替对账。数据库中的 `QUARANTINED`、`AVAILABLE`、`ARCHIVED` 资源都不是孤儿；归档也不是可删除证明。对象存储容量不足时，应先扩容或停止上传，不能以紧急处置为由跳过上述证据边界。

## 9. 备份与恢复边界

场景恢复必须同时覆盖管理 PostgreSQL 与对象存储，同一个恢复点缺一不可：

- PostgreSQL：`mine_scene_asset`、`mine_scene_asset_file`、`mine_scene`、`mine_scene_version`、`mine_scene_layer`、`mine_scene_action_idempotency` 及审计事实。
- 对象存储：`scene/` 内容对象和 bucket 版本/策略；Standalone 的 `rustfs_data` 卷只是本机数据，不算主机外备份。
- 前端制品：与版本锁匹配的 Cesium Workers/Assets/Widgets/ThirdParty 和应用 chunk。

恢复到隔离环境后，先校验数据库约束和迁移，再逐摘要抽查文件数、大小与 SHA-256，最后验证默认运行快照、入口 Range、确定性 GLB、二维降级和权限隔离。没有实际恢复演练报告时，不得声称场景备份可恢复。

## 10. 故障处置和安全回退

### 10.1 存储或读取故障

切换二维并确认位置/业务事实仍更新；检查 S3 endpoint、DNS、证书、bucket、凭据最小权限、磁盘和探针。修复后先验证探针，再抽查入口 0 字节 Range 和完整加载。不得把新内容覆盖到旧摘要键。

### 10.2 快照持续降级

按固定 `reason` 排查默认场景、发布状态、schema、图层数量、发布摘要和入口可读性。若错误版本尚未设为默认，停止切换；若已经默认，创建/选择另一个具有已验证发布版本的场景并通过受权 API 切换默认。禁止修改已发布版本或数据库 `default_scene`。

### 10.3 WebGL/Cesium 故障

使用二维保持生产可见性，记录浏览器、操作系统、GPU、驱动、WebGL 能力和控制台固定错误；验证 Cesium 本地静态资源是否完整。真实目标矩阵未验证前，不以单台开发机成功代替客户环境证据。

### 10.4 实时子链路故障

分别核对位置、空间事件、告警和运营 REST/订阅状态。继续展示最后数据及陈旧状态；位置连接缺口发送 WebSocket `RESYNC`，空间事件和运营缺口按各自固定水位 REST 回查。禁止清零、跨矿区复用缓存或在浏览器内猜测业务事实。

### 10.5 安全停止与恢复

要停止三维治理时，撤销上传/编辑/发布权限并停止上传，不删除默认场景和对象。要完全维护对象存储时，提前通知并让用户切换二维；数据库、位置、告警和运营服务保持运行。恢复顺序为：对象存储探针 → 数据库/对象抽查 → 默认快照 → 同源资源读取 → 三维渲染 → 各实时叠加 → 恢复上传和发布权限。

任何情况下都禁止修改不可变发布事实、替换摘要内容、绕过 API 使用任意外链、清理资源卷、回滚 Flyway 或让浏览器直连对象存储凭据。

## 11. 生产放行前仍需完成

- 真实矿坑、道路、设备 GLB/3D Tiles 的来源、许可证、尺寸、LOD、坐标和精度验收。
- 现场统一坐标、场景原点/旋转/缩放与实测定位叠加误差报告。
- 客户支持的浏览器、操作系统、GPU/驱动和 1366×768、1920×1080、大屏矩阵。
- 正式共享 S3 的 TLS、加密、最小权限、版本控制、容量、并发、故障切换和恢复。
- 目标车辆/人员/区域/告警/轨迹数量下的帧率、内存、首屏、资源流量和服务读取容量。
- mine-server、数据库、对象存储、网络和浏览器故障注入及 HA 恢复时间。
- 管理库与对象存储同恢复点的主机外备份恢复演练。
- 丢包、抖动、限速、断连、缓存失效和连续 24/72 小时资源趋势。

这些项目按当前决策 `DEFERRED`，但全部阻止“已经取得生产上线资格”的结论。
