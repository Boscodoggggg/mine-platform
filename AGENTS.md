# AGENTS.md

## 代码规范
- 后端java代码必须写中文注释，注释风格采用Javadoc

## 提交规范

- 提交信息必须使用中文描述变更内容。
- 提交格式遵循 Angular / Conventional Commits 规范：

```text
<type>(<scope>): <中文说明>
```

- `scope` 可省略；如果能明确模块，建议填写，例如 `frontend`、`backend`、`governance`、`docs`。
- 常用 `type`：
  - `feat`：新增功能
  - `fix`：缺陷修复
  - `docs`：文档变更
  - `style`：格式或样式调整，不影响逻辑
  - `refactor`：重构
  - `test`：测试相关
  - `chore`：构建、依赖、脚本或杂项

示例：

```text
feat(governance): 新增工程治理资产管理
fix(frontend): 修复详情页文件树展示
docs: 补充 RBAC 设计说明
```
