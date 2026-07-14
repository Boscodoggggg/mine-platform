# Phase 0 工程底座验收报告

> 验收日期：2026-07-14
>
> 验收分支：`feat/phase-0-foundation`
>
> 验收范围：`docs/plans/2026-07-14-phase-0-foundation.md` 的 Task 1～14
>
> 当前结论：**待复验，不允许标记为完整通过**

## 1. 结论摘要

Phase 0 的后端模块骨架、认证与权限加载、RabbitMQ 双 vhost、三个可运行服务、Vue 3 管理端底座、单机 Compose、基础可观测性、CI 和 SBOM 已完成。

单机环境已在保留数据卷的情况下使用 `--pull never` 完成离线重启，核心服务、HTTPS、MQTTS、PostgreSQL、TimescaleDB、Redis、RabbitMQ、Prometheus、Grafana、Loki 和 Alloy 均通过自动验收。

当前仍有两个放行项未闭环：

1. 未提交的登录页改动与测试不一致，导致全仓门禁有 1 个前端用例失败。
2. 当前实现包含五表 RBAC、Sa-Token 权限加载和动态菜单契约，但没有角色/用户/菜单授权的管理端 CRUD API 与页面，因此原计划中的“创建角色并重新授权”无法通过产品界面手工验收。

## 2. 自动化验收结果

| 验收项 | 命令或方式 | 结果 | 说明 |
|---|---|---:|---|
| 后端单元、集成和架构测试 | `./mvnw -B -ntp verify` | 通过 | 9 个 Maven reactor 模块全部成功，RabbitMQ/PostgreSQL/Redis Testcontainers 集成测试通过 |
| 前端类型检查 | `pnpm --dir mine-web typecheck` | 通过 | 无 TypeScript/Vue 类型错误 |
| 前端规范检查 | `pnpm --dir mine-web lint:check` | 通过 | oxlint、ESLint 均为 0 warning / 0 error |
| 前端单元测试 | `pnpm --dir mine-web test` | **失败** | 10 个用例中 9 个通过；登录页测试期待 `.mine-scene-asset`，当前未提交页面不存在该元素 |
| 前端生产构建 | `pnpm --dir mine-web build` | 通过 | Vite production build 成功 |
| GitHub Actions 语法 | `actionlint v1.7.12` | 通过 | `.github/workflows/ci.yml` 无错误 |
| Compose 配置 | `docker compose --profile observability ... config --quiet` | 通过 | 核心和可观测 profile 均可解析 |
| 核心服务冒烟 | `deploy/scripts/health-check.sh` | 通过 | HTTPS、MQTTS、数据库、TimescaleDB 扩展、RabbitMQ、Redis 均正常 |
| 可观测性冒烟 | `deploy/tests/observability-smoke.sh` | 通过 | 6 个 Prometheus target、Grafana dashboard、Loki traceId 日志反查均正常 |
| 离线重启与持久化 | `down` 后以 `up -d --pull never` 重启 | 通过 | 未重新拉取镜像，保留数据卷后服务和初始管理员仍可用 |
| 初始管理员登录 | HTTPS `/api/v1/auth/login`、`/me` | 通过 | 登录和当前用户接口返回 200，初始密码强制修改标记为 `true` |
| 首次改密边界 | 改密前访问 `/api/v1/auth/menus` | 通过 | 返回 403 / `AUTH-403-003`，未改初始密码不能进入业务菜单 |
| SBOM 和许可证 | `bash scripts/generate-sbom.sh` | 通过 | 生成 CycloneDX 1.6；后端 152、前端 941 个组件，未发现 AGPL、SSPL、Commons Clause |

## 3. 全仓门禁失败证据

执行：

~~~bash
bash scripts/verify.sh
~~~

后端阶段完整通过，前端类型检查和 lint 通过，随后在以下断言失败：

~~~text
LoginPage.test.ts:46
Unable to get .mine-scene-asset
~~~

涉及文件均属于当前工作树中尚未提交的登录页设计改动：

- `mine-web/src/modules/system/pages/login/LoginPage.vue`
- `mine-web/src/modules/system/pages/login/LoginPage.test.ts`
- `mine-web/src/assets/images/smart-mine-tunnel.svg`
- 已删除的 `mine-web/src/assets/images/login-mine-landscape.jpg`

验收过程没有修改、回退或暂存这些并行改动。

## 4. 尚未完成的手工验收

| 项目 | 状态 | 放行要求 |
|---|---|---|
| 首次登录后修改密码并重新登录 | 待执行 | 使用现场保管的新密码完成改密，确认旧会话失效、新密码可登录 |
| 创建普通角色、分配目录/菜单/按钮权限 | 功能缺口 | 先实现受超级管理员保护的用户、角色、菜单授权 CRUD API 和管理页面 |
| 授权变更后重新登录生效 | 自动测试已覆盖，手工待执行 | 管理页面完成后按真实操作链路复验 |
| Grafana 人工查看服务和 RabbitMQ | 自动 API 验收通过，人工待确认 | 现场运维账号登录并确认总览图可读 |
| 现场断公网启动 | 本机 `--pull never` 已通过，现场待确认 | 离线发行包导入目标服务器后，在真实防火墙策略下复验 |

## 5. 复验步骤

1. 合并或完成登录页改动，确保 `pnpm --dir mine-web test` 全绿。
2. 明确角色/用户/菜单授权管理是否纳入 Phase 0；若纳入，完成 API、页面、审计和权限测试。
3. 重新执行 `bash scripts/verify.sh`，必须返回 0。
4. 执行首次改密、角色授权、重新登录和 Grafana 人工检查。
5. 更新本报告结论为“通过”，记录验收人、制品版本、镜像 digest 和现场环境信息。

## 6. 生产运行边界

本报告只证明 Phase 0 工程底座和单机运行基线，不代表智慧矿山一期已经具备生产放行条件。真实设备协议、弱网补传、命令闭环、5000 客户端压测、72 小时稳定性、备份恢复演练和矿区网络安全验收仍需在后续阶段完成。
