# Restore Main History Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 `feat/phase-0-foundation` 的完整开发历史接入公开 `main`，同时保留开源后产生的修复并恢复严格分支保护。

**Architecture:** 原开发分支最终文件树与公开基线提交完全一致，因此以原开发分支为父链，依次重放公开基线之后的两个修复提交，再加入本迁移记录。远端更新使用带租约的强制推送，并在操作前后保存和核验保护规则。

**Tech Stack:** Git、GitHub CLI、GitHub Branch Protection API、GitHub Actions

---

### Task 1: 冻结迁移边界

**Files:**
- Create: `docs/plans/2026-07-22-restore-main-history.md`

**Step 1: 校验工作区和远端同步**

Run: `git status --short --branch && git fetch origin --prune`

Expected: `main` 与 `origin/main` 指向同一提交，工作区除本计划外无修改。

**Step 2: 创建本地回滚引用**

Run: `git branch backup/public-main-before-full-history-20260722 origin/main`

Expected: 回滚分支指向迁移前公开 `main`。

**Step 3: 校验两个基线文件树一致**

Run: `git diff --quiet feat/phase-0-foundation cf10cced9db980842dbb211c775d419c22ab2305`

Expected: 退出码为 0。

### Task 2: 重建完整主线

**Files:**
- Modify through history replay: `.github/workflows/ci.yml`
- Modify through history replay: `.github/dependabot.yml`
- Create: `docs/plans/2026-07-22-restore-main-history.md`

**Step 1: 从完整开发历史创建迁移分支**

Run: `git switch -c restore/full-main-history feat/phase-0-foundation`

Expected: 新分支以 323 个原始提交为历史基础。

**Step 2: 重放公开发布后的修复**

Run: `git cherry-pick 06783afe678d8db4e13584fcd97cf94d0fc9b8e0 53fa9dfb623201bbd60ae957a4e3e55448c82ddf`

Expected: 两个提交无冲突重放。

**Step 3: 提交迁移记录**

Run: `git add docs/plans/2026-07-22-restore-main-history.md && git commit -m "docs(governance): 记录主分支完整历史迁移"`

Expected: 形成第 326 个提交。

**Step 4: 校验最终代码等价**

Run: `git diff --quiet backup/public-main-before-full-history-20260722 HEAD -- . ':(exclude)docs/plans/2026-07-22-restore-main-history.md'`

Expected: 除迁移记录外，最终文件树与迁移前 `main` 完全一致。

### Task 3: 更新 GitHub 主分支

**Files:**
- Modify: GitHub `main` branch history
- Modify: GitHub `main` branch protection

**Step 1: 临时允许管理员历史迁移**

Run: 使用 GitHub Branch Protection API 临时关闭管理员强制保护并允许强制推送。

Expected: `enforce_admins=false`、`allow_force_pushes=true`，其余门禁保持不变。

**Step 2: 带租约更新远端**

Run: `git push --force-with-lease=main:53fa9dfb623201bbd60ae957a4e3e55448c82ddf origin HEAD:main`

Expected: 仅当远端仍处于已审计提交时才完成历史替换。

**Step 3: 立即恢复严格保护**

Run: 使用 GitHub Branch Protection API 恢复管理员保护并禁止强制推送。

Expected: `enforce_admins=true`、`allow_force_pushes=false`、禁止删除，且四项 CI 仍为必需检查。

### Task 4: 验证迁移结果

**Files:**
- Verify: GitHub `main`

**Step 1: 同步本地主分支**

Run: `git branch -f main HEAD && git switch main`

Expected: 本地 `main` 与 `origin/main` 指向相同的新提交。

**Step 2: 验证提交图与文件树**

Run: 校验远端提交数量、原开发分支祖先关系、迁移前后代码等价和工作区清洁度。

Expected: `main` 包含完整原始历史，提交数量为 326，代码只新增本迁移记录。

**Step 3: 等待主分支 CI**

Run: `gh run watch <run-id> --repo Boscodoggggg/mine-platform --exit-status`

Expected: 后端、前端、部署配置、SBOM 与许可证四项检查全部通过。
