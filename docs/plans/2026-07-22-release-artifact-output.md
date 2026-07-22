# Release 构建产物输出 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让标准 Maven 与前端 build 命令自动把三个可执行 JAR 和一个前端 ZIP 输出到仓库根目录的扁平 `release/`，无需额外手工打包命令。

**Architecture:** 各 Spring Boot 模块在 `package` 阶段完成 repackage 后复制自己的可执行 JAR 到 `release/<artifactId>.jar`。前端 `pnpm build` 在 Vite 成功后调用 Node 打包器，把 `mine-web/dist` 内容压缩为 `release/mine-web.zip`；`release/` 作为本机构建产物保持 Git 忽略，现有 `deploy/` 安装流程不改变。

**Tech Stack:** Maven、Spring Boot Maven Plugin、Maven Antrun Plugin、Node.js、zip.js、pnpm、Bash。

---

### Task 1: 固化 release 产物契约

**Files:**
- Create: `build-tests/release-artifacts.sh`
- Create: `build-tests/frontend-release-reproducibility.sh`
- Create: `build-tests/maven-release-publication.sh`

**Step 1: Write the failing test**

要求 `release/mine-server.jar`、`release/mine-gateway.jar`、`release/mine-telemetry.jar` 和 `release/mine-web.zip` 存在；三个 JAR 必须包含 `BOOT-INF/`，ZIP 必须在根层包含 `index.html` 且不能额外包一层 `dist/`。

使用 `TZ=UTC` 与 `TZ=Asia/Shanghai` 对同一份 `dist/` 连续打包，要求 SHA-256 完全一致，避免 ZIP DOS 本地时间破坏跨时区可重复构建。

要求 Maven 先复制到 `release/*.jar.tmp`，再移动为最终 JAR，避免构建过程中暴露半写入制品。

**Step 2: Run test to verify it fails**

Run: `bash build-tests/release-artifacts.sh`

Expected: FAIL，当前没有 `release/` 构建产物。

Run: `bash build-tests/maven-release-publication.sh`

Expected: FAIL，当前 Maven 尚未通过临时文件发布 release JAR。

### Task 2: Maven package 自动发布三个 JAR

**Files:**
- Modify: `mine-server/pom.xml`
- Modify: `mine-gateway/pom.xml`
- Modify: `mine-telemetry/pom.xml`

**Step 1: Bind artifact copy to package**

在三个 Spring Boot 模块的 `spring-boot-maven-plugin` 之后绑定 `maven-antrun-plugin:run` 到 `package`，把已 repackage 的 `${project.build.directory}/${project.build.finalName}.jar` 分别复制到根目录临时文件，再移动为 `release/${project.artifactId}.jar`。

**Step 2: Verify Maven output**

Run: `./mvnw -B -ntp -pl mine-gateway,mine-telemetry,mine-server -am package -DskipTests`

Expected: 三个 release JAR 存在且含 Spring Boot `BOOT-INF/`。

### Task 3: pnpm build 自动发布前端 ZIP

**Files:**
- Create: `mine-web/scripts/package-release.mjs`
- Modify: `mine-web/package.json`
- Modify: `mine-web/pnpm-lock.yaml`

**Step 1: Add deterministic ZIP packager**

使用直接声明的 `@zip.js/zip.js` 开发依赖，按稳定文件顺序把 `dist/` 内容压缩到临时文件，再原子替换 `../release/mine-web.zip`；缺少 `dist/index.html` 时失败。

**Step 2: Bind it to the standard build command**

把 `build` 改为 `vite build --mode prod && node scripts/package-release.mjs`，不新增需要用户手动执行的命令。

**Step 3: Verify frontend output**

Run: `pnpm --dir mine-web build`

Expected: `release/mine-web.zip` 生成且能通过 ZIP 完整性检查。

### Task 4: 文档、忽略规则与完整门禁

**Files:**
- Modify: `.gitignore`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `deploy/README.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `scripts/verify.sh`

**Step 1: Document standard commands and outputs**

说明 `./mvnw package` 与 `pnpm --dir mine-web build` 会自动生成扁平 `release/`，`prepare-artifacts.sh` 仅用于把现有构建结果安装到 `deploy/`，不是额外的打包步骤。

**Step 2: Run all relevant gates**

Run: `bash build-tests/release-artifacts.sh`

Run: `bash build-tests/maven-release-publication.sh`

Run: `bash build-tests/frontend-release-reproducibility.sh`

Run: `bash deploy/tests/prepare-artifacts.sh && bash deploy/tests/offline-bundle.sh`

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Expected: 全部 PASS。

**Step 3: Commit**

```bash
git add .gitignore .github README.md README.zh-CN.md build-tests deploy docs mine-server mine-gateway mine-telemetry mine-web scripts
git commit -m "feat(build): 统一输出 release 发行制品"
```
