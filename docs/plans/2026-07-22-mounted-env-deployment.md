# 挂载式环境变量部署 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把单机部署收敛为一个可直接交付的 `deploy/` 目录：Java 通用镜像挂载三个 JAR，Nginx 通用镜像挂载前端产物，密码和运行参数统一由 `deploy/.env` 提供，证书及大段静态配置继续只读挂载。

**Architecture:** `deploy/docker-compose.yml` 是唯一生产 Compose 入口，默认从 `deploy/backend/`、`deploy/frontend/dist/`、`deploy/config/` 和 `deploy/certs/` 挂载文件。真实 `deploy/.env` 永不提交，模板 `deploy/.env.example` 提供全部键；每个服务仅注入自身需要的变量。Java 启动脚本把环境变量中的敏感值写入容器临时只读文件后清除进程环境，以兼容现有文件型应用配置。

**Tech Stack:** Docker Compose v2、Bash、Eclipse Temurin Java 21 JRE、Nginx Alpine、Maven、pnpm。

---

### Task 1: 固化简化目录和 env 部署契约

**Files:**
- Create: `deploy/tests/deployment-layout.sh`
- Modify: `deploy/tests/compose-config.sh`
- Modify: `deploy/tests/java-artifact-isolation.sh`

**Step 1: Write the failing test**

新增契约，要求 `deploy/docker-compose.yml`、`deploy/.env.example`、`backend/`、`frontend/`、`certs/` 存在；Compose 不得再声明密码 secret，三个 Java 服务必须挂载独立 JAR，Nginx 必须挂载前端目录和证书目录，密码必须从 env 注入。

**Step 2: Run test to verify it fails**

Run: `bash deploy/tests/deployment-layout.sh`

Expected: FAIL，因为新目录和新 Compose 尚不存在。

**Step 3: Extend existing contracts**

把现有 Compose JSON 断言从 `/run/secrets/*` 改成环境变量或容器临时 secret 文件，并增加 Java 启动脚本环境变量落盘、权限和清理断言。

**Step 4: Re-run targeted tests**

Run: `bash deploy/tests/deployment-layout.sh`

Expected: 在实现前继续 FAIL，且失败原因指向缺少新布局。

### Task 2: 实现根目录 Compose、env 和挂载式运行

**Files:**
- Move: `deploy/compose/compose-standalone.yml` -> `deploy/docker-compose.yml`
- Move: `deploy/env/standalone.env.example` -> `deploy/.env.example`
- Create: `deploy/backend/.gitkeep`
- Create: `deploy/frontend/.gitkeep`
- Create: `deploy/certs/README.md`
- Modify: `deploy/scripts/run-java-service.sh`
- Modify: `deploy/scripts/rabbitmq-bootstrap.sh`
- Modify: `deploy/scripts/timescale-reader-init.sh`
- Modify: `deploy/config/nginx/nginx.conf`
- Modify: `deploy/config/rabbitmq/rabbitmq-standalone.conf`
- Modify: `.gitignore`

**Step 1: Implement minimal Compose layout**

让三个 Java 服务分别挂载 `${MINE_*_JAR_PATH}` 到 `/opt/mine/source/app.jar`，Nginx 挂载 `${MINE_WEB_DIST_PATH}`，所有静态配置路径改为相对 `deploy/` 根目录，TLS 证书从 `${MINE_CERTS_DIR}` 只读挂载。

**Step 2: Implement env credential delivery**

移除 Compose 的密码 secret 声明；PostgreSQL、TimescaleDB、Redis、RabbitMQ、Exporter 和 Grafana 使用各自的直接环境变量。Java 启动脚本只为当前服务收到的敏感变量生成 `0400` 临时文件，设置现有 `_FILE` 配置后清除明文变量。

**Step 3: Add safe template and ignored runtime directories**

`deploy/.env.example` 列出镜像、制品路径、端口、账号、密码、数据密钥及业务开关；真实 `.env`、JAR、前端 dist 和证书内容保持 Git 忽略。

**Step 4: Run targeted tests**

Run: `bash deploy/tests/deployment-layout.sh && bash deploy/tests/java-artifact-isolation.sh && bash deploy/tests/compose-config.sh`

Expected: PASS。

### Task 3: 让部署脚本和离线包交付单一 deploy 目录

**Files:**
- Create: `deploy/scripts/prepare-artifacts.sh`
- Create: `deploy/scripts/start.sh`
- Create: `deploy/scripts/stop.sh`
- Create: `deploy/scripts/restart.sh`
- Create: `deploy/scripts/status.sh`
- Modify: `deploy/scripts/preflight.sh`
- Modify: `deploy/scripts/health-check.sh`
- Modify: `deploy/scripts/install-offline.sh`
- Modify: `deploy/scripts/build-offline-bundle.sh`
- Modify: `deploy/scripts/backup-standalone.sh`
- Modify: `deploy/scripts/restore-standalone.sh`
- Modify: `deploy/tests/offline-bundle.sh`
- Modify: `deploy/tests/standalone-backup.sh`
- Modify: `deploy/tests/standalone-restore.sh`
- Modify: `deploy/tests/observability-smoke.sh`
- Modify: `deploy/tests/standalone-smoke.sh`

**Step 1: Update failing packaging assertions**

离线包测试要求归档中包含 `deploy/docker-compose.yml`、`deploy/.env.example`、`deploy/backend/*.jar` 和 `deploy/frontend/dist/index.html`，且不包含真实 `.env` 或证书私钥。

**Step 2: Verify RED**

Run: `bash deploy/tests/offline-bundle.sh`

Expected: FAIL，因为打包器仍输出旧模块目录。

**Step 3: Implement scripts and packaging**

提供制品准备和 start/stop/restart/status 包装脚本；所有运维脚本默认使用 `deploy/.env` 与 `deploy/docker-compose.yml`；离线归档把全部运行文件收敛到 `deploy/`。

**Step 4: Verify GREEN**

Run: `bash deploy/tests/offline-bundle.sh && bash deploy/tests/standalone-backup.sh && bash deploy/tests/standalone-restore.sh`

Expected: PASS。

### Task 4: 更新工程入口、文档并执行全量部署门禁

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/dependabot.yml`
- Modify: `scripts/verify.sh`
- Modify: `deploy/README.md`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/runbooks/*.md`（仅更新当前有效部署命令和凭据说明）

**Step 1: Update active references**

把当前文档和自动化入口统一为 `deploy/.env`、`deploy/.env.example` 和 `deploy/docker-compose.yml`；历史 `docs/plans/` 保持不改。

**Step 2: Run syntax and deployment checks**

Run: `find build-tests scripts deploy/scripts deploy/tests -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n`

Run: `docker compose --profile observability --env-file deploy/.env.example -f deploy/docker-compose.yml config --quiet`

Run: `bash deploy/tests/deployment-layout.sh && bash deploy/tests/compose-config.sh && bash deploy/tests/java-artifact-isolation.sh && bash deploy/tests/offline-bundle.sh && bash deploy/tests/standalone-backup.sh && bash deploy/tests/standalone-restore.sh`

Expected: 全部 PASS。

**Step 3: Run repository quality gates**

Run: `./mvnw -B -ntp verify`

Run: `pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Expected: 全部 PASS。

**Step 4: Commit**

```bash
git add .github .gitignore README.md README.zh-CN.md deploy docs scripts
git commit -m "feat(deploy): 简化挂载式单机部署"
```
