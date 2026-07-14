# 07 Vue 3 前端设计

## 1. 技术选择

前端以 SoybeanAdmin Naive UI 版本为工程底座，保留：

- 布局、主题、登录页和错误页。
- 动态路由和菜单框架。
- 请求封装、国际化基础和图标体系。
- Naive UI、Pinia、UnoCSS 和 TypeScript 工程规范。

不直接采用其第三方后端、Mock 数据、业务权限模型和示例页面。矿山页面、API 契约和实时数据层全部自研。

## 2. 目录结构

~~~text
mine-web/src/
├── app/                       # 启动、路由、权限和全局插件
├── layouts/                   # Soybean布局适配
├── modules/
│   ├── dashboard/
│   ├── monitoring/
│   ├── device/
│   ├── protocol/
│   ├── strategy/
│   ├── vehicle/
│   ├── personnel/
│   ├── route/
│   ├── uwb/
│   ├── operation/
│   ├── command/
│   ├── alarm/
│   ├── scene3d/
│   ├── operations/
│   └── system/
├── realtime/                  # 独立高频数据层
├── shared/
│   ├── api/
│   ├── components/
│   ├── contracts/
│   ├── hooks/
│   └── utils/
└── styles/
~~~

每个 modules 子目录可包含 pages、components、api、stores、types 和 tests。模块外只能导入其公开 index，不导入内部相对路径。

## 3. 页面信息架构

| 一级模块 | 主要页面 |
|---|---|
| 总览 | 设备在线、告警、消息速率、运行摘要 |
| 实时监控 | 动态设备仪表、当前作业、实时位置 |
| 设备中心 | 产品、设备、分组、凭据、在线会话 |
| 协议中心 | 协议定义、样本、调试、版本和插件 |
| 策略中心 | 物模型、映射、质量、告警、保留、配置发布 |
| 车辆管理 | 车辆档案、设备绑定、状态 |
| 人员管理 | 人员、司机资质、车辆/标签绑定 |
| 路线管理 | 路线、途径点、围栏 |
| UWB | 标签、实时位置、轨迹、区域事件 |
| 运营数据 | 班次、作业单、作业记录、统计 |
| 命令中心 | 模板、下发、状态时间线和回复 |
| 告警中心 | 活动告警、历史、确认和恢复 |
| 三维场景 | 场景、图层、车辆/人员/告警叠加 |
| 运维中心 | 服务状态、积压、节点配置、备份任务 |
| 系统管理 | 用户、角色、菜单、日志和字典 |

## 4. 路由、菜单和权限

登录后：

1. `GET /api/v1/auth/me` 获取用户基本信息。
2. `GET /api/v1/auth/menus` 一次返回角色码、权限码和 sys_menu 授权树。
3. 前端把后端 `componentKey` 映射到 `componentRegistry` 中明确注册的本地页面组件。
4. BUTTON 类型不生成路由，只控制按钮/操作可见性。
5. 页面展示权限只改善体验，后端 API 注解才是最终安全边界。

禁止后端返回任意组件路径让前端动态 import，防止加载未授权模块或形成远程代码执行入口。未知
`componentKey` 使用本地占位页面，并在浏览器控制台记录未注册键；它不会回退到字符串拼接 import。

首批实现位置：

~~~text
src/app/router/dynamic-routes.ts       componentKey 白名单、路由和菜单转换
src/app/router/DynamicFallbackPage.vue 未注册组件的安全降级页
src/app/permission/permission.ts       v-permission 指令与纯权限判断函数
src/store/modules/route/index.ts       登录后装载授权快照和动态路由
~~~

页面组件注册示例：

~~~ts
const componentRegistry = {
  'home-overview': () => import('@/views/home/index.vue'),
  'device-list': () => import('@/modules/device/pages/DeviceListPage.vue')
};
~~~

后端菜单管理页只能从前端发布版本支持的 `componentKey` 字典中选择。新页面首次开发时要同时完成：组件注册、
页面路由测试、后端接口权限、按钮权限和菜单数据配置。菜单发布不等于上传前端代码。

按钮权限用法：

~~~vue
<!-- 单权限 -->
<NButton v-permission="'device:device:create'">新增设备</NButton>

<!-- 数组表示满足任意一项；all 表示必须全部满足 -->
<NButton v-permission="['device:device:update', 'device:device:archive']">维护设备</NButton>
<NButton v-permission="{ all: ['protocol:definition:test', 'protocol:definition:publish'] }">发布协议</NButton>
~~~

`v-permission` 只隐藏无权限操作；直接调用 API 时后端仍必须通过 `@SaCheckPermission` 拒绝越权请求。

权限码示例：

~~~text
device:device:list
device:device:create
protocol:definition:publish
strategy:package:rollback
command:instance:create
system:audit:list
~~~

## 5. 状态管理

Pinia 适合：

- 当前用户和权限。
- 菜单和主题。
- 低频筛选条件。
- 当前编辑草稿摘要。

Pinia 不适合每秒更新成千上万位置点。高频数据由 RealtimeHub 管理：

~~~text
单个 WebSocket
→ 协议解码
→ topic订阅表
→ 内存最新值缓存
→ 批量/节流通知
→ 监控组件或Cesium适配器
~~~

RealtimeHub 职责：

- 连接、心跳、指数退避重连。
- 登录过期处理。
- subscribe/unsubscribe 引用计数。
- sequence 检测和断线后快照重同步。
- requestAnimationFrame 或时间窗批量通知。
- 页面不可见时降低刷新频率。

## 6. WebSocket 协议

客户端请求：

~~~json
{
  "action": "SUBSCRIBE",
  "requestId": "uuid",
  "topics": [
    {"type": "DEVICE_LATEST", "ids": ["device-001"]},
    {"type": "LOCATION", "groupId": "haul-trucks"}
  ]
}
~~~

服务端响应：

~~~json
{
  "type": "DATA",
  "sequence": 1024,
  "serverTime": "2026-07-14T08:00:00Z",
  "topic": "DEVICE_LATEST",
  "items": []
}
~~~

重连后先请求当前快照，再接增量，不能只靠“从断点继续”假设服务器保留无限历史。

## 7. 动态设备监控页面

页面输入：

- thingModelVersion。
- displaySchemaVersion。
- 当前属性。
- 活动告警。
- 用户权限。

组件注册表：

~~~text
number      → NumericCard
gauge       → GaugeCard
progress    → ProgressCard
enum        → StatusBadge
boolean     → BooleanIndicator
trend       → TrendSparkline
location    → LocationSummary
~~~

未知 widget 使用通用只读组件并记录前端告警，不能导致整页白屏。

## 8. 协议工作台

采用三栏或上下组合：

- 左侧：协议版本、消息类型和样本。
- 中间：帧结构、字段表、十六进制输入和分段着色。
- 右侧：解析树、校验过程、物模型映射和错误。

关键交互：

- 修改后明确显示未保存/未测试。
- 单样本调试与全量回归分开。
- 发布前显示旧新结果差异。
- 大报文使用虚拟列表/Canvas，避免数千字节 DOM 节点。

## 9. 策略工作台

布局：

~~~text
顶部：草稿 / 测试 / 影响预览 / 发布 / 回滚
左侧：产品 → 设备组 → 设备作用范围
中间：当前策略编辑器
右侧：有效值、继承来源、冲突和校验结果
~~~

保留、告警、映射等策略各自编辑，但发布时组成一个配置包。前端必须提示“保存草稿不等于运行生效”。

## 10. 命令页面

- 只展示设备当前配置包支持的命令。
- 参数表单由 command schema 生成。
- 高风险命令使用醒目但不过度刺激的警告，要求输入原因并二次确认。
- 提交后展示状态时间线，不用一个“发送成功”绿色标签结束。
- 网络失败时使用 Idempotency-Key 查询原提交结果，避免重复下发。

## 11. 三维场景

- Cesium 模块路由级异步加载。
- 模型和瓦片从矿区本地对象存储加载，不依赖 Cesium ion。
- RealtimeHub 直接向 Cesium adapter 提供批量位置，不把全部 Entity 放 Pinia。
- 车辆图标/模型使用对象池，历史轨迹按视距和时间窗抽稀。
- WebGL 不可用或模型缺失时降级到二维列表/地图，不影响管理功能。

## 12. 请求层与契约

- OpenAPI 作为 REST 契约来源，生成 TypeScript 类型和基础 client。
- 生成代码放 shared/api/generated，不手工编辑。
- 业务模块包装生成 client，处理页面语义。
- 错误统一展示 code、message 和 traceId。
- 401 统一清理会话并跳转登录；403 展示无权限；409 展示冲突详情；422 展示字段/规则错误。

## 13. 弱网前端体验

- 页面顶部显示 API 和 WebSocket 独立状态。
- 实时数据卡片显示最后更新时间和“数据已陈旧”。
- 断线时保留最后数据但改变质量状态，不清零伪装正常。
- 编辑草稿定期保存到后端；本地只做防意外临时缓存。
- 发布、命令等写操作不能在浏览器离线队列中自动重放。

## 14. 性能目标

- 管理页面不加载 Cesium。
- 高频更新不触发整个页面树重渲染。
- 大列表必须分页或虚拟滚动。
- 历史曲线由后端聚合，前端不一次加载百万点。
- 路由 chunk、图标、模型和 source map 纳入离线包体积检查。
- 真实 1366×768、1920×1080 和大屏分辨率测试；关键操作不只适配设计稿宽屏。

## 15. 前端测试

- Vitest + Vue Test Utils：动态表单、权限指令、策略继承展示、RealtimeHub。
- Mock Service Worker 或类型安全 stub：API 边界。
- Playwright：登录、角色菜单、设备注册、协议发布、策略回滚、命令下发和告警确认。
- Cesium 单元测试 mock adapter，另设真实浏览器演示场景 smoke test。
- 不以快照测试代替行为断言。

动态授权至少覆盖：DIRECTORY 层级保留、BUTTON 不生成路由、未知组件降级、任一/全部权限语义、超级管理员
`*` 通配权限。组件白名单测试不能通过 mock 任意 import 来弱化安全边界。

## 16. UI 设计原则

- 运行状态使用文字 + 图标 + 颜色，不能只靠颜色。
- 告警颜色与普通品牌色分离。
- 表格和配置页面优先清晰、密度适中，不追求炫技。
- 危险操作按钮位置稳定，避免误触。
- 所有时间展示矿区时区，并可查看 UTC/设备原始时间。
- 字段单位、精度和质量来自物模型，不在组件中硬编码。
