# 中间件常驻端口与 RustFS 控制台 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在受控矿区内网部署中，将 PostgreSQL、TimescaleDB、Redis、RabbitMQ AMQP/管理页面、Gateway Binary TCP 和 RustFS 控制台常驻发布到宿主机，并允许通过 `deploy/.env` 调整宿主端口。

**Architecture:** 保持容器内部端口和服务发现地址不变，只在 Compose `ports` 层新增可配置宿主端口。所有新增映射默认监听宿主全部网卡，认证继续复用现有 env 凭据；RustFS 使用固定镜像已支持的 `RUSTFS_CONSOLE_ENABLE`、`RUSTFS_CONSOLE_ADDRESS` 和 `9001` 控制台端口。

**Tech Stack:** Docker Compose、Bash、jq、RustFS、PostgreSQL、TimescaleDB、Redis、RabbitMQ。

---

### Task 1: 固化外部端口契约

**Files:**
- Modify: `deploy/tests/compose-config.sh`

**Step 1: Write the failing test**

要求渲染后的 Compose 同时发布以下端口，且不设置回环 `host_ip`：PostgreSQL `5432`、TimescaleDB `5433 -> 5432`、Redis `6379`、RabbitMQ `5672/15672/8883`、Gateway `9000`、RustFS Console `9001`。要求 RustFS 控制台显式启用并监听 `0.0.0.0:9001`。

**Step 2: Run test to verify it fails**

Run: `bash deploy/tests/compose-config.sh`

Expected: FAIL，指出 RustFS 控制台仍关闭或中间件端口未发布。

### Task 2: 实现 Compose 与 env 配置

**Files:**
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/.env.example`

**Step 1: Publish configurable ports**

增加 `MINE_POSTGRES_PORT`、`MINE_TIMESCALE_PORT`、`MINE_REDIS_PORT`、`MINE_RABBITMQ_AMQP_PORT`、`MINE_RABBITMQ_MANAGEMENT_PORT` 和 `MINE_RUSTFS_CONSOLE_PORT`。把 `MINE_GATEWAY_TCP_PUBLISHED_ADDRESS` 默认值改为 `0.0.0.0`。

**Step 2: Enable RustFS console**

设置 `RUSTFS_CONSOLE_ENABLE=true`、`RUSTFS_CONSOLE_ADDRESS=0.0.0.0:9001` 并发布控制台端口。

**Step 3: Run focused tests**

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/deployment-layout.sh`

Expected: PASS。

### Task 3: 更新部署文档与完整门禁

**Files:**
- Modify: `deploy/README.md`
- Modify: `docs/09-deployment-operations.md`

**Step 1: Document connection endpoints and boundary**

记录所有默认宿主端口、用途、凭据来源和仅适用于无公网端口转发的受控矿区内网前提。

**Step 2: Run relevant gates**

Run: `docker compose --profile observability --env-file deploy/.env.example -f deploy/docker-compose.yml config --quiet`

Run: `bash deploy/tests/deployment-layout.sh && bash deploy/tests/compose-config.sh && bash deploy/tests/offline-bundle.sh`

Run: `find deploy/scripts deploy/tests -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n`

Expected: 全部 PASS。

**Step 3: Commit**

```bash
git add deploy docs/plans/2026-07-22-published-middleware-ports.md docs/09-deployment-operations.md
git commit -m "feat(deploy): 开放内网中间件与 RustFS 控制台端口"
```
