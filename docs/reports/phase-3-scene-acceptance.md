# Phase 3 三维场景自动化验收报告

## 1. 结论

- 验收日期：2026-07-20
- 自动化结论：`AUTOMATED PASS`
- 生产结论：`PRODUCTION NOT RELEASED`
- 验收范围：三维资源安全上传与受权读取、标准 S3 存储、场景版本/发布/默认治理、完整运行快照、Cesium 离线加载、二维独立降级、位置/空间事件/告警/生产运营叠加、确定性演示、指标告警和部署契约。

仓库自动化证明本修订在受控测试环境中的契约和降级行为可复现，可用于演示、非现场联调和继续产品化开发。它不证明真实模型、矿区坐标、客户 GPU/浏览器、共享对象存储、目标容量、高可用、备份恢复、弱网或 24/72 小时长稳已经满足商用 SLA。

## 2. 已验收能力

| 范围 | 自动化证据 | 结论 |
|---|---|---|
| 资源安全入口 | GLB 和 3D Tiles ZIP 白名单、流式大小限制、Zip Slip/绝对路径/符号链接/重复路径/压缩炸弹/外部 URI 拒绝 | 通过 |
| 内容寻址存储 | 上传 SHA-256、同矿区摘要幂等、文件清单、原子文件系统发布、通用 S3 适配与 RustFS Compose | 通过 |
| 受权读取 | 权限与矿区隔离、数据库清单先行、安全相对路径、同源 URL、ETag、304、单 Range 206、非法范围拒绝和 `nosniff` | 通过 |
| 场景控制面 | 稳定场景、单草稿/单当前发布、乐观锁、图层整体替换、固定类型和上限、发布摘要、持久幂等、默认唯一和归档约束 | 通过 |
| 不可变运行快照 | 默认发布版本完整读取、schema/摘要/图层/入口复核，半包只返回固定 `DEGRADED` 原因 | 通过 |
| 前端治理 | “三维场景”菜单、资源上传/归档、场景/草稿/图层/发布/默认工作台、精确 RBAC、双语与主题治理 | 通过 |
| Cesium 离线边界 | 路由级懒加载、本地 Workers/Assets/Widgets/ThirdParty、无 ion、受权同源 GLB/3D Tiles、离开页面销毁 Viewer | 通过 |
| 二维独立降级 | WebGL 不可用时不创建三维视口；二维仍接收位置、业务事实并支持矿区切换；单资源失败只降级对应图层 | 通过 |
| 矿区事实叠加 | 当前位置、轨迹、区域、空间事件、告警、当前班次、运行作业和运营标记独立加载与降级 | 通过 |
| 确定性演示 | 仓库自制 CC0 GLB、固定生成器与 SHA-256、真实 WebGL 浏览器回归 | 通过 |
| 可观测性 | 存储创建/读/删探针、三项低基数指标、四项 Prometheus 告警及行为测试 | 通过 |
| 部署契约 | S3/RustFS、独立暂存卷、生产 bucket 初始化边界、演示摘要和 Compose 配置脚本 | 通过 |

## 3. 关键不变量

1. 用户提供的 `siteId` 和资源 URL 不是授权凭据；服务端同时校验权限、组织和矿区数据范围。
2. 内容对象由上传 SHA-256 寻址，同摘要内容不得替换；浏览器只能读取数据库清单内的同源受权路径。
3. 场景发布版本、图层和摘要不可变；变更只通过更高草稿/发布版本表达。
4. 发布和默认切换使用持久幂等键，乐观冲突必须刷新后人工决定。
5. 运行接口只返回完整 `READY` 快照或不含半包的固定 `DEGRADED` 结果。
6. Cesium 只在三维路由加载；管理页面不得请求其 chunk、Workers、Assets、Widgets 或 ThirdParty。
7. 三维、二维和业务事实解耦；WebGL、静态资源或单个实时子链路失败不能清空其他已提交事实。
8. 位置连接序号缺口通过 WebSocket `RESYNC/SNAPSHOT` 重同步；空间事件和运营业务序号缺口通过固定水位 REST 回查，浏览器不能猜测遗漏状态。
9. 场景存储探针不加入二维 readiness；对象存储故障只阻断三维资源能力。
10. 指标禁止携带组织、矿区、场景、资源、文件路径、用户和异常文本等高基数标签。

## 4. 自动化执行证据

本次收口执行：

```bash
./mvnw -B -ntp test
./mvnw -B -ntp verify -DskipUnitTests
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
pnpm --dir mine-web test:e2e -- mine-scene-workbench.spec.ts
bash deploy/tests/compose-config.sh
bash deploy/tests/prometheus-rules.sh
```

最终执行结果：

- Maven 12 个 Reactor 模块全部成功；本次新生成的 Surefire 报告共 878 项单元测试通过，失败、错误和跳过均为 0。
- PostgreSQL、TimescaleDB、RabbitMQ 和 Redis 容器集成的 Failsafe 报告共 347 项通过，失败、错误和跳过均为 0。
- 前端 UI 治理 47 项通过，类型检查通过；静态检查覆盖 395 个文件、141 条规则，警告和错误均为 0。
- Vitest 65 个测试文件共 405 项通过；生产构建通过。
- Playwright `mine-scene-workbench.spec.ts` 共 4 项通过：真实 WebGL 确定性 GLB、GLB 500 单图层降级、WebGL 不可用二维仍接收位置/业务并切换矿区、非三维路由不加载 Cesium。
- Compose 配置和确定性演示摘要校验通过，演示 GLB SHA-256 为 `4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78`。
- Prometheus 共 54 条规则通过语法检查；四项场景告警通过阈值、最小样本和持续时间行为测试。

首次全量集成门禁发现两项历史测试基线与当前产品行为不一致：矿区不存在已经正确映射为 HTTP 404，运营修正测试使用固定结束时间导致在当前日期违反时间线约束。测试已改为断言 404，并基于数据库实际开始时间完成作业；针对性 21 项及后续全量门禁均通过。该修正没有放宽生产约束。

测试数量来自本次生命周期完成后新生成的 Surefire/Failsafe/Vitest 报告，不复用旧阶段数字，也不把目标测试留下的旧 XML 重复计数。

## 5. 数据库、接口和权限证据

- V50 创建 `mine_scene_asset`、`mine_scene_asset_file`、`mine_scene`、`mine_scene_version`、`mine_scene_layer`，保护跨组织/矿区引用、摘要格式、文件清单、生命周期、单草稿、单发布、默认唯一、图层顺序和发布不可变。
- V50.1 增加资源乐观版本；V50.2 增加场景版本乐观锁和发布/默认/归档持久幂等账本 `mine_scene_action_idempotency`。
- V51 发布“三维场景”菜单和 `view/edit/asset:upload/publish` 四类精确权限。
- 服务端把全部图层、默认相机和二维视口纳入发布摘要；V50 的数据库触发器阻止发布后版本和子事实修改或删除。
- 所有新增表和字段均有 `COMMENT ON TABLE/COLUMN` 中文注释；真实 PostgreSQL 集成测试校验注释、约束、触发器和升级迁移。

资源接口只接受 multipart 上传和受控查询，不接受对象存储键或任意外链。文件读取先用 `siteId + assetId + relativePath` 命中数据库清单，再打开内部内容寻址对象；响应使用白名单媒体类型、私有不可变缓存、ETag 和单 Range。

场景接口分别使用 `mine:scene:view`、`mine:scene:edit`、`mine:scene:asset:upload`、`mine:scene:publish`。归档、发布和默认切换要求稳定 `Idempotency-Key` 与当前乐观版本。精确请求/响应以 [Mine OpenAPI](../openapi/mine-api.yaml) 为准。

## 6. 前端和降级证据

- `/mine/scene` 通过后端菜单的 `mine-scene-workbench` 白名单组件加载；未知组件不能拼路径动态导入。
- Cesium 静态目录由构建插件复制并通过本地 `CESIUM_BASE_URL` 解析，不配置 ion token 或公网底图。
- 运行快照在进入页面时读取，前端再次校验 schema、有限数值、图层数量、内容摘要格式和同源资源 URL。
- 静态 GLB/3D Tiles、位置、轨迹、区域、空间事件、告警和运营叠加分别维护状态；单链路失败不会重置其他链路。
- WebGL 不可用时自动显示二维视口；用户也可显式选择二维，二维不依赖 Cesium Viewer 生命周期。
- 位置使用快照后接实时增量，连接序号缺口发送 WebSocket `RESYNC`；空间事件和运营按业务水位/连续序号发现缺口后有界 REST 回查。矿区切换释放订阅、取消支持 AbortSignal 的请求，并以世代号隔离不能取消的迟到响应。

运行、告警、对账和恢复步骤见 [矿区三维场景运行与安全回退手册](../runbooks/mine-scene-runtime.md)。

## 7. 明确暂缓的真实验证

| 生产门禁 | 状态 | 需要的后续证据 |
|---|---|---|
| 真实模型与许可证 | `DEFERRED` | 客户矿坑/道路/设备 GLB 或 3D Tiles 来源、许可、LOD、纹理、尺寸和安全扫描报告 |
| 真实坐标与精度 | `DEFERRED` | 统一坐标、原点/旋转/缩放、模型控制点与实测定位叠加误差报告 |
| GPU/浏览器矩阵 | `DEFERRED` | 客户操作系统、浏览器、GPU/驱动、分辨率、WebGL 能力、帧率和显存/内存报告 |
| 共享 S3 | `DEFERRED` | 正式 S3 的 TLS、加密、最小权限、版本控制、对象锁、并发和故障恢复报告 |
| 目标容量 | `DEFERRED` | 模型/瓦片体积、并发用户、Range 读取、车辆/人员/区域/轨迹/告警/作业数量及容量余量 |
| 高可用和故障注入 | `DEFERRED` | mine-server、数据库、S3、网络、浏览器故障切换和恢复时间 |
| 备份恢复 | `DEFERRED` | 管理库与对象存储同恢复点、主机外备份、摘要/清单对账和完整页面恢复演练 |
| 弱网 | `DEFERRED` | 丢包、抖动、限速、断连、Range 重试、缓存失效和实时缺口恢复报告 |
| 24/72 小时长稳 | `DEFERRED` | 浏览器帧率/内存、服务堆/线程、对象存储延迟、错误率和实时一致性趋势 |
| 现场运维 | `DEFERRED` | 告警送达、值班升级、孤儿对象变更单、备份责任人与恢复演练审计 |

这些项目不阻止后续仓库产品化开发，但全部阻止“可在任意矿区直接生产上线”或“已经满足商业 SLA”的对外声明。

## 8. Phase 3 收口与后续路线

Phase 3 仓库自动化范围现已完成：矿区作用域与资产、发生时绑定、双坐标、UWB 位置、路线/围栏/空间事件、班次/作业单/生产运营以及三维/二维可视化形成可复现纵向闭环。

下一阶段进入 Phase 4 商用加固：

1. 以客户/矿区为单位完成本报告 `DEFERRED` 的真实模型、坐标、GPU/浏览器和共享 S3 验证。
2. 建立目标容量、HA、备份恢复、弱网、故障注入、供应链和 24/72 小时长稳报告。
3. 真实设备和现场材料就绪后，合并定位、空间事件、运营和三维证据，形成客户级生产放行报告。

在上述生产门禁完成前，产品状态保持 `PRODUCTION NOT RELEASED`。
