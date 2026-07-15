# AGENTS.md

## 代码规范
- 后端java代码必须写中文注释，注释风格采用Javadoc

## 数据库规范

- 所有新增数据库表必须提供中文表注释，并为表中的每一个字段提供中文字段注释，禁止遗漏表注释或任意字段注释。
- PostgreSQL/TimescaleDB 迁移统一使用 `COMMENT ON TABLE` 和 `COMMENT ON COLUMN` 编写注释；对应数据库集成测试必须校验表和字段注释完整。

## 前端主题、样式与国际化规范

- `mine-web/src/theme/settings.ts` 是前端颜色、圆角、阴影和主题模式默认值的唯一配置入口。
- 业务页面必须优先使用 Naive UI 组件；布局和间距可使用 UnoCSS 原子类，不得在业务页面编写 `<style>`、内联 CSS 或页面私有样式表。
- 业务代码不得硬编码十六进制、RGB、HSL 等颜色值，也不得使用任意值颜色类。状态颜色使用 Naive UI 的 `primary`、`success`、`warning`、`error`、`info` 语义类型；确需使用颜色时只能引用主题 token。
- 亮色、暗色和跟随系统模式必须由 `useThemeStore` 统一管理。页面不得自行读取 `matchMedia`、创建主题状态或使用独立的 localStorage 键保存主题模式。
- 所有用户可见文案必须放入 `mine-web/src/locales/langs/zh-cn.ts` 和 `mine-web/src/locales/langs/en-us.ts`，组件通过 Vue I18n 获取；按钮、标题、占位符、校验提示、消息、空状态、无障碍标签等均不得硬编码。
- 新增或修改业务页面后必须执行 `pnpm --dir mine-web check:ui-governance`，并保证 typecheck、lint、unit test 和 build 通过。

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
