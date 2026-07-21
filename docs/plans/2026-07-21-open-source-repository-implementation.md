# Open Source Repository Launch Implementation Plan

> 本文记录首次公开仓库发布的可复现实施步骤与验证证据。

**Goal:** 将当前已验证代码发布为采用 GitHub Flow、具备中英双语首页和完整社区健康基线的公开 GitHub 主仓库。

**Architecture:** 以当前功能分支的验证结果建立独立开源快照根提交，避免公开内部研发邮箱历史，也不制造人工 merge 历史；后续所有变更通过短分支、PR、CI 和 squash merge。仓库级静态检查保护 README 互链、社区文件和模板，GitHub 端启用默认分支、Topics、安全披露与主分支保护。

**Tech Stack:** Git、GitHub CLI/API、GitHub Actions、Bash、Markdown、YAML、现有 Maven/pnpm/Compose 质量门禁。

---

### Task 1：建立社区健康失败契约

**Files:**
- Create: `build-tests/community-health.sh`
- Test targets: `README.md`, `README.zh-CN.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `.github/ISSUE_TEMPLATE/*.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS`

**Steps:**

1. 编写静态检查，要求中英 README 互链、许可证/贡献/安全/部署/文档入口存在，社区文件和模板齐全，并拒绝未替换占位符。
2. 运行 `bash build-tests/community-health.sh`，确认因中文 README 和社区文件尚不存在而 RED。
3. 提交失败契约：`test(governance): 定义开源社区健康契约`。

### Task 2：重构中英双语项目首页

**Files:**
- Rewrite: `README.md`
- Create: `README.zh-CN.md`

**Steps:**

1. 英文首页加入定位、徽章、差异化能力、Mermaid 架构、快速开始、成熟度、路线图、文档和贡献入口。
2. 中文首页保持同等信息密度和准确状态。
3. 运行社区健康检查，确认只因其余社区文件缺失而失败。
4. 提交：`docs: 重构中英双语开源项目首页`。

### Task 3：补齐社区协作与安全资产

**Files:**
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `CODE_OF_CONDUCT.md`
- Create: `.github/CODEOWNERS`
- Create: `.github/PULL_REQUEST_TEMPLATE.md`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`
- Create: `docs/15-branching-and-release.md`
- Modify: `docs/README.md`

**Steps:**

1. 落实 GitHub Flow、分支命名、PR、CI、发布 tag 和 hotfix 规则。
2. 落实贡献、行为准则、公开支持和私密漏洞披露边界。
3. 提供结构化 Bug/Feature Issue Forms 与 PR 清单。
4. 运行 `bash build-tests/community-health.sh`，预期 GREEN。
5. 提交：`docs(governance): 建立开源协作与分支治理`。

### Task 4：把社区健康检查纳入门禁

**Files:**
- Modify: `scripts/verify.sh`
- Modify: `.github/workflows/ci.yml`

**Steps:**

1. 在本地验证入口和 CI 部署作业加入 `bash build-tests/community-health.sh`。
2. 运行社区检查、Shell 语法、Maven、前端和部署专项门禁。
3. 提交：`chore(governance): 纳入开源社区健康门禁`。

### Task 5：公开发布并保护 main

**Files:**
- Git/GitHub state only.

**Steps:**

1. 检查工作树、敏感文件、大文件、Git 历史和最终差异。
2. 从当前已验证工作树创建独立 orphan `main` 快照，使用 GitHub noreply 邮箱提交；保留原功能分支仅用于本地研发，不把它的历史推送到公开仓库。
3. 验证公开 `main` 只有一个根提交、提交邮箱为 noreply，且树对象与已验证快照一致。
4. 使用 GitHub CLI 创建不含代码的公开 `Boscodoggggg/mine-platform`，立即启用 Private Vulnerability Reporting，并通过 API 与匿名 HTTP 状态验证入口不是 404。
5. 推送 `main`，设置描述、Topics、Issues、squash merge、自动删除分支和默认分支，等待首次 CI。
6. CI 通过后启用 `main` 保护：PR、对话解决、四类 CI、线性历史、禁 force push/删除。
7. 验证公开 README、社区健康、安全入口、规则和 clone URL。
