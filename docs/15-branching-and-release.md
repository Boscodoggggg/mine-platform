# 分支、Pull Request 与版本发布策略

## 1. 目标

mine-platform 采用 GitHub Flow：让 `main` 始终保持可构建、可验证和可发布，同时让外部贡献者只需理解短分支、Pull Request 和 CI。当前不维护长期 `develop` 分支，也不为单机/五节点、客户或功能维护不同产品分支。

本策略参考 GitHub 对受保护分支、Rulesets、Pull Request 和社区健康文件的官方建议，并结合项目当前 Pre-1.0、小型维护团队和离线发行特点制定。

## 2. 长期引用

| 引用 | 用途 | 规则 |
|---|---|---|
| `main` | 唯一长期开发主线 | 始终通过质量门禁；日常禁止直接推送 |
| `vMAJOR.MINOR.PATCH` | 不可变正式版本 | 指向通过发行门禁的 `main` 提交，并创建 GitHub Release |
| `release/x.y` | 可选并行稳定分支 | 只有确实需要同时稳定 minor 系列时创建，不作为日常开发入口 |

首次公开发布直接以当前已验证功能分支的头提交建立 `main`，不制造把全部历史再次包裹起来的巨型 merge commit。

## 3. 工作分支

所有普通变更从最新 `main` 创建，一个分支只解决一个可验证问题：

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feat/` | 新能力 | `feat/pgbackrest-pitr` |
| `fix/` | 缺陷修复 | `fix/realtime-sequence-gap` |
| `docs/` | 文档 | `docs/vendor-onboarding` |
| `refactor/` | 不改变外部行为的重构 | `refactor/protocol-registry` |
| `test/` | 测试与验证资产 | `test/weak-network-matrix` |
| `chore/` | 构建、依赖和治理 | `chore/release-sbom` |
| `hotfix/` | 已发布版本的紧急修复 | `hotfix/0.2.1` |

分支使用小写英文、数字和连字符，尽量在数日内完成并删除。不得用 `user/dev/latest/final` 等不可读名称，不得建立长期个人集成分支。

## 4. Pull Request

1. 从最新 `main` 创建工作分支。
2. 测试先行，实现最小完整闭环；同步更新契约、部署与手册。
3. 使用中文 Conventional Commit，PR 最终标题也必须符合 `<type>(<scope>): <中文说明>`。
4. 填写仓库 PR 模板，给出问题、范围、红绿证据、兼容性、迁移/回退和生产边界。
5. 后端、前端、部署和 SBOM 门禁全部成功，评审对话全部解决。
6. 默认 Squash Merge，删除远端工作分支。

Critical/Important 审查问题未解决、数据库迁移不可恢复、权限/矿区隔离不明确、凭据泄露或把自动化冒充现场验收时不得合并。

## 5. `main` 保护

GitHub 端对 `main` 启用：

- 要求 Pull Request；
- 要求后端、前端、部署配置和 SBOM/许可证检查成功；
- 要求解决全部对话；
- 要求线性历史；
- 禁止 force push 和删除；
- 默认关闭 merge commit 与 rebase merge，只保留 squash merge。

仓库管理员保留平台故障时的紧急恢复权限，但正常变更不得绕过 PR 和 CI。需要紧急修复时使用 `hotfix/*`，缩小改动范围并补齐回归测试。

## 6. 版本与发布

项目使用语义化版本：

- `MAJOR`：公开契约或部署边界存在明确不兼容变更；
- `MINOR`：向后兼容的新能力；
- `PATCH`：向后兼容的缺陷、安全或文档修复。

Pre-1.0 阶段仍使用 `0.MINOR.PATCH`，但数据库、消息、协议和设备身份契约不能因版本小于 1.0 就任意破坏。

发布流程：

1. 从 `main` 选择通过完整门禁的提交。
2. 固定应用和基础镜像 digest，生成离线包、SHA-256、CycloneDX SBOM、许可证和升级/恢复说明。
3. 执行自动化门禁与适用的现场验收，不复用旧版本报告。
4. 创建带注释的 `vX.Y.Z` tag 和 GitHub Release，附变更、兼容性、迁移、回退、已知限制和制品校验和。
5. 正式制品不可覆盖；修复使用更高版本。

## 7. Hotfix 与并行稳定

已发布版本发生高优先级缺陷时，从对应 tag 创建 `hotfix/x.y.z`。修复先写失败测试，经 PR 合入 `main`；若仍维护 `release/x.y`，再通过明确 cherry-pick PR 回补，不允许把整个 `main` 隐式合入旧系列。

只有客户发布窗口确实要求在 `main` 继续开发时稳定一个 minor 版本，才从候选提交创建 `release/x.y`。该分支只接受阻断发布的修复、版本和文档，不接受新功能；系列结束后停止维护并保留 tag，不保留僵尸分支。

## 8. 禁止事项

- 禁止强推或删除 `main` 和正式 tag。
- 禁止直接在 `main` 上开发后补 PR。
- 禁止把客户、矿区或部署拓扑做成长期产品分支。
- 禁止提交 secret、备份、客户数据、私钥、构建目录和真实敏感坐标。
- 禁止跳过 CI、数据库迁移验证、升级/回退说明或现场生产门禁。
- 禁止为了整洁重写已经公开的版本历史。
