# mine-platform 开源仓库与分支治理设计

## 1. 目标

把当前功能分支收口为公开、可贡献、可持续发布的 GitHub 主仓库，同时用真实能力和清晰边界完成中英双语项目介绍。目标是降低第一次理解、试用和贡献的成本，而不是通过夸大生产成熟度换取短期关注。

公开仓库固定为 `Boscodoggggg/mine-platform`，默认分支为 `main`，采用 Apache-2.0。仓库描述、Topics、社区健康文件、CI、Dependabot、SBOM 和安全披露入口共同组成对外可信度基线。

## 2. 分支策略选择

评估三种常见方案：

1. Git Flow：长期维护 `main` 与 `develop`，再叠加 feature/release/hotfix。适合固定发布列车和多版本并行，但当前维护团队小，会增加同步与回合成本。
2. GitHub Flow：`main` 始终可发布，所有变更走短生命周期分支、Pull Request 和 CI；版本通过不可变 tag/Release 表达。适合持续迭代的开源产品。
3. Trunk-based：允许极短分支甚至直接提交主干，依赖强自动化和 feature flag。吞吐高，但公开仓库早期直接写主干会降低外部贡献审查质量。

采用 GitHub Flow。首发时把当前已验证代码导入为独立开源快照根提交，不公开内部研发分支的个人邮箱历史，也不制造无意义的巨型 merge commit；此后禁止日常直接推送。功能分支使用 `feat/*`、`fix/*`、`docs/*`、`refactor/*`、`test/*` 和 `chore/*`，发布补丁使用 `hotfix/*`，全部通过 PR 合入。默认使用 squash merge，合并后删除分支。

版本发布使用 `vMAJOR.MINOR.PATCH` tag 和 GitHub Release。只有需要并行稳定某个 minor 系列时才创建 `release/x.y`；紧急修复从对应发布 tag 创建 `hotfix/x.y.z`。若 `main` 尚未前进，修复经 PR 合入 `main` 后发布；若 `main` 已含下一版本变更，则经 PR 合入 `release/x.y` 并从该分支发布，再用独立 cherry-pick PR 回补 `main`。当前不创建长期 `develop` 分支。

## 3. 主分支保护

首次推送并获得 CI 上下文后，为 `main` 启用保护：

- 必须通过 Pull Request 合并并解决全部对话。
- 必须通过后端、前端、部署和 SBOM 四类 CI。
- 禁止 force push 和删除主分支。
- 保持线性历史，使用 squash merge。
- 管理员保留紧急恢复能力，但不得把它当作日常绕过流程。

贡献者无需写权限，可从 fork 提交 PR。安全漏洞不进入公开 Issue，统一使用 GitHub Private Vulnerability Reporting。

## 4. 对外首页信息架构

根 `README.md` 使用英文作为默认入口以服务全球开发者，`README.zh-CN.md` 提供完整中文版本。两者保持相同结构：

1. 一句话定位、CI/许可证/技术栈徽章和语言入口。
2. 为什么需要矿山场景专用 IoT，而不是通用后台换皮。
3. 已实现能力、架构图和核心安全/可靠性不变量。
4. 五分钟构建验证与 Standalone 离线部署入口。
5. 当前成熟度、明确未完成的真实现场门禁和路线图。
6. 文档、贡献、安全和许可证入口。

首页不伪造客户、生产规模、性能数字或真实送达结果。仓库自动化通过与生产放行严格分离，真实设备、容量、HA、恢复和长稳继续标记为现场门禁。

## 5. 社区与自动化

新增 `CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、Issue Forms、PR 模板和 CODEOWNERS。贡献文档明确分支命名、中文 Conventional Commit、TDD、数据库中文注释、前端主题/i18n 规则和质量门禁。

新增社区健康静态检查，验证关键文件、语言互链、Issue/PR 模板和核心相对链接存在，并把它接入本地 `scripts/verify.sh` 与 GitHub Actions。该检查防止首页或贡献入口随代码演进失效。

## 6. 发布边界

本次只发布代码、文档和自动化，不发布现场环境文件、secret、证书、备份、客户数据或构建缓存。Git 历史在公开前执行敏感信息与大文件扫描；发现疑似凭据时停止发布并先清理历史。

公开仓库上线后配置准确描述与 Topics，开启 Issues、Private Vulnerability Reporting、自动删除已合并分支和 squash merge。高 Star 只能来自持续交付、可信文档和真实社区运营，本设计不承诺具体 Star 数量。
