# Contributing to mine-platform

Thank you for helping build open, reliable infrastructure for smart mines. Contributions are welcome from device vendors, mine operators, system integrators, researchers and software engineers.

[中文说明](#中文贡献说明) · [Architecture docs](docs/README.md) · [Branching strategy](docs/15-branching-and-release.md) · [Code of Conduct](CODE_OF_CONDUCT.md)

## Before opening an issue

- Search existing issues and the [roadmap](docs/12-roadmap-and-codex-workflow.md).
- Use the structured Bug or Feature form so maintainers can reproduce and classify the request.
- Remove credentials, customer names, exact mine coordinates, personal data and proprietary protocol material that you are not allowed to disclose.
- Report vulnerabilities through the private process in [SECURITY.md](SECURITY.md), never through a public issue.

Questions that depend on a specific deployment should include sanitized topology, software versions and the expected/actual boundary. “It does not work” without reproducible evidence is not actionable.

## Development workflow

mine-platform follows GitHub Flow. `main` is the protected, releasable branch; normal work happens on short-lived branches and enters through a Pull Request.

1. Fork the repository and synchronize your fork with `main`.
2. Create one focused branch:

   ```bash
   git switch -c feat/short-capability-name
   ```

3. Write a failing test or executable contract first, then implement the smallest complete behavior.
4. Update API, message, database, deployment and runbook documentation with the code they describe.
5. Run the relevant local tests and the complete gate before requesting review.
6. Open a Pull Request using the repository template; keep it small enough to review and revert safely.

Supported prefixes are `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, `chore/` and release-only `hotfix/`. Do not create a long-lived personal integration branch or submit unrelated formatting changes with a feature.

## Commit messages

Commits use Angular/Conventional Commits with a concise Chinese description:

```text
<type>(<scope>): <中文说明>
```

Examples:

```text
feat(gateway): 支持矿卡二进制登录帧
fix(telemetry): 拒绝乱序消息倒退当前值
docs(deploy): 补充离线恢复演练步骤
```

Allowed types include `feat`, `fix`, `docs`, `style`, `refactor`, `test` and `chore`. A Pull Request is normally squash-merged, so its final title must follow the same format.

## Engineering rules

### Backend

- Use Java 21 and follow the existing module boundaries. Do not introduce cross-module shortcuts that violate ArchUnit rules.
- New and modified Java APIs require Chinese Javadoc explaining business intent and failure boundaries.
- Use real PostgreSQL/TimescaleDB, RabbitMQ and Redis integration tests where production semantics matter; H2 and in-memory queues do not prove compatibility.
- Stable protocol, API and message contracts must remain backward compatible or introduce an explicit version.

### Database

- Every new PostgreSQL/TimescaleDB table and column requires a Chinese comment using `COMMENT ON TABLE` and `COMMENT ON COLUMN`.
- Database integration tests must verify that comments, constraints, indexes and migrations are complete.
- Keep simple CRUD SQL in Mapper annotations and move complex joins, CTEs, windows and large dynamic SQL to mapper XML with focused tests.

### Frontend

- Use Naive UI components and the shared theme entry at `mine-web/src/theme/settings.ts`.
- Do not hard-code colors or create page-local `<style>` blocks. Use theme tokens and UnoCSS layout utilities.
- All visible text, placeholders, errors and accessibility labels must exist in both Chinese and English locale files.
- Run UI governance, type checking, lint, unit tests and the production build for every business-page change.

### Reliability and security

- Device identity comes from trusted transport context, not from an arbitrary payload field.
- Preserve sequence, boot identity, event time, idempotency and Inbox/Outbox semantics.
- Never log or commit passwords, tokens, Webhooks, AccessKeys, private keys, customer data or exact sensitive coordinates.
- Recovery, retention and destructive operations must fail closed and must not silently overwrite evidence.

## Quality gates

Run the full repository gate from the project root:

```bash
bash scripts/verify.sh
```

For a faster module loop, run the focused command first and the full gate before review:

```bash
./mvnw -B -ntp test
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
bash build-tests/community-health.sh
```

Pull Requests that change database integration, browser behavior or deployment topology must add the corresponding Testcontainers, Playwright or Compose evidence. Automated success must not be described as real-site production acceptance.

## Pull Request review

Reviewers look for:

- a clear problem and bounded scope;
- red/green evidence for new behavior;
- compatibility, authorization and mine-scope isolation;
- migration, rollback and operational observability;
- accurate documentation and no exaggerated maturity claims;
- no secrets, customer data, generated build output or unrelated files.

Critical and Important review findings must be resolved before merge. Discussion is encouraged; bypassing CI or force-pushing `main` is not.

## 中文贡献说明

本项目欢迎设备厂商、矿山业主、集成商和开发者参与。提交前请先搜索 Issue 和路线图；漏洞必须走 [安全披露流程](SECURITY.md)，公开材料不得包含客户名称、真实凭据、人员信息、精确敏感坐标或无权公开的厂商协议。

开发遵循“短分支 → 测试先行 → 中文 Conventional Commit → Pull Request → CI → Squash Merge”。后端 Java 代码使用中文 Javadoc；新增数据库表和字段必须具有中文注释并由集成测试校验；前端文案必须同时进入中英文语言包。完整规则以上文和根目录 `AGENTS.md` 为准。

By submitting a contribution, you agree that it may be distributed under the repository's [Apache License 2.0](LICENSE).
