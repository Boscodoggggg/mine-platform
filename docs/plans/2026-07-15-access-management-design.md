# 用户、角色与授权管理设计

> 日期：2026-07-15
>
> 状态：已确认，可进入实施
>
> 返回契约：保持现有 HTTP 状态码、成功 DTO 直返、失败 `ApiErrorResponse`，不增加统一 `Result<T>`

## 1. 目标与边界

本功能补齐五表 RBAC 从“数据库结构和运行时鉴权”到“管理员可以实际维护”的闭环。超级管理员可以创建和维护普通用户、创建和维护角色、给用户分配角色、给角色勾选目录/页面/按钮权限。权限修改后不重启服务；`StpInterfaceImpl` 每次鉴权实时读取 PostgreSQL，因此已有登录会话在下一次请求时即可看到新权限。

菜单定义不允许管理员在前台任意创建或修改。目录、页面路径、`componentKey` 和按钮权限码属于代码契约，由 Flyway 随版本发布。管理页面只读展示菜单树并允许角色勾选，避免错误路径、未知组件或权限码被现场人员配置后导致管理端无法进入。普通角色不预置；首次部署仍只初始化一个由 secret 创建的超级管理员。

本批实现用户列表、用户创建、基本资料与状态修改、临时密码重置、用户角色替换；实现角色列表、创建、修改、删除、角色菜单替换；实现只读授权树。暂不实现组织机构、数据权限、批量导入、用户物理删除、角色继承和跨矿区租户。用户不物理删除，停用后由现有 `AccountStatusGuard` 立即使会话失效。

## 2. 安全模型

超级管理员由 `mine.security.bootstrap-admin.id` 唯一识别，是虚拟 `SUPER_ADMIN` 角色和 `*` 权限的拥有者。它不能被停用、重置密码、修改角色或删除。普通管理员只能调用自己拥有权限码的接口，后端使用 `@SaCheckPermission` 强制校验，前端按钮隐藏只改善体验，不能代替后端鉴权。

权限码固定为：

| 资源 | 权限码 |
|---|---|
| 查看用户 | `system:user:view` |
| 创建用户 | `system:user:create` |
| 修改用户 | `system:user:update` |
| 分配用户角色 | `system:user:assign-role` |
| 重置临时密码 | `system:user:reset-password` |
| 查看角色 | `system:role:view` |
| 创建角色 | `system:role:create` |
| 修改角色 | `system:role:update` |
| 删除角色 | `system:role:delete` |
| 分配角色权限 | `system:role:assign-menu` |

用户创建和密码重置必须提供 12～72 字节临时密码，服务端使用现有 BCrypt 配置散列，数据库只保存散列，并强制用户下次登录修改。密码不得进入响应、操作日志或异常详情。重置后注销该用户全部旧会话。普通用户状态仅允许 `ENABLED`、`DISABLED`、`LOCKED`；管理端日常操作只开放启用和停用，锁定状态由安全策略使用。

## 3. 数据与并发

继续使用 `sys_user`、`sys_role`、`sys_menu`、`sys_user_role` 和 `sys_role_menu`，不新增 RBAC 主表。Flyway V3 插入确定 UUID 的“系统管理”目录、用户管理页面、角色管理页面和对应按钮节点；脚本必须幂等语义清晰，但迁移本身只执行一次。

用户和角色修改请求携带 `version`。更新 SQL 使用 `WHERE id = ? AND version = ?` 并令 `version = version + 1`；影响行数为零时重新判断资源是否存在，不存在返回 404，版本冲突返回 409。角色编码创建后不可修改，避免审计、脚本和外部集成引用漂移。删除角色时，如果仍分配给任何用户则返回 409，管理员必须先解除用户关联。系统菜单不删除，角色授权采用事务内“删除旧关联、批量插入新关联”的替换语义。

列表接口使用统一 `PageResponse<T>`：`items`、`page`、`size`、`total`。页码从 1 开始，`size` 最大 100。关键词只匹配用户名/显示名或角色编码/名称，并进行参数绑定，禁止拼接 SQL。所有写操作在 Spring 事务中完成，关联目标必须全部存在且启用；请求包含重复 ID 时先去重。

## 4. HTTP API

| 方法 | 路径 | 权限 | 成功状态 | 用途 |
|---|---|---|---:|---|
| GET | `/api/v1/system/users` | `system:user:view` | 200 | 分页查询用户 |
| POST | `/api/v1/system/users` | `system:user:create` | 201 | 创建普通用户 |
| PUT | `/api/v1/system/users/{id}` | `system:user:update` | 200 | 修改显示名、状态 |
| PUT | `/api/v1/system/users/{id}/roles` | `system:user:assign-role` | 204 | 原子替换用户角色 |
| POST | `/api/v1/system/users/{id}/password-reset` | `system:user:reset-password` | 204 | 设置临时密码并踢下线 |
| GET | `/api/v1/system/roles` | `system:role:view` | 200 | 分页查询角色 |
| POST | `/api/v1/system/roles` | `system:role:create` | 201 | 创建角色 |
| PUT | `/api/v1/system/roles/{id}` | `system:role:update` | 200 | 修改名称、状态、描述 |
| DELETE | `/api/v1/system/roles/{id}` | `system:role:delete` | 204 | 删除未分配角色 |
| PUT | `/api/v1/system/roles/{id}/menus` | `system:role:assign-menu` | 204 | 原子替换授权节点 |
| GET | `/api/v1/system/menus/tree` | `system:role:view` | 200 | 读取完整只读权限树 |

创建接口通过 `Location` 响应头返回资源地址。业务错误使用稳定错误码，例如 `SYSTEM-404-001` 用户不存在、`SYSTEM-404-002` 角色不存在、`SYSTEM-409-001` 用户名重复、`SYSTEM-409-002` 角色编码重复、`SYSTEM-409-003` 乐观锁冲突、`SYSTEM-409-004` 角色仍被用户使用、`SYSTEM-403-001` 禁止修改超级管理员。

## 5. 审计与可观测性

所有成功写操作追加 `sys_operation_log`，模块为 `SYSTEM_ACCESS`，动作包括 `CREATE_USER`、`UPDATE_USER`、`ASSIGN_USER_ROLES`、`RESET_USER_PASSWORD`、`CREATE_ROLE`、`UPDATE_ROLE`、`DELETE_ROLE`、`ASSIGN_ROLE_MENUS`。记录操作者、资源 ID、权限码、requestId、traceId、来源 IP、User-Agent 和脱敏前后摘要。摘要只保存用户名、显示名、状态、角色 ID、角色编码和菜单 ID，不保存密码、密码散列或令牌。

未登录、无权限和业务失败继续由统一异常处理输出 `ApiErrorResponse`。前端显示中文 `message` 和可复制 `traceId`，不展示堆栈。数据库唯一约束异常在服务内转换为 409；未知数据库异常保持 500 并只写后端日志。角色授权规模、用户创建和密码重置后续增加 Micrometer 计数器不是本批阻塞项。

## 6. 前端交互

动态路由注册表增加 `system-user-list` 和 `system-role-list` 两个本地组件键。用户管理页包含查询、分页、创建、编辑、启停、分配角色和重置密码；角色管理页包含查询、分页、创建、编辑、删除和菜单树授权。按钮根据 `hasPermission` 显示，但 API 的 403 仍由统一请求客户端处理。

菜单授权树展示目录、页面和按钮三类节点，按钮显示权限码；勾选子节点不要求用户手工勾选祖先，后端导航查询会自动补齐祖先。保存前展示变更确认。重置密码弹窗不回显密码，提交成功立即清空表单。所有请求期间禁用重复提交，409 显示“数据已被其他管理员修改，请刷新后重试”。

前端从 `auth.ts` 抽出公共 HTTP 客户端，统一处理 JSON、204、401、403 和 `ApiErrorResponse`，但不改变现有成功 DTO 直返方式。登录页现有并行设计改动不在本功能中修改。

## 7. 验收标准

- 超级管理员可以创建普通用户和角色，并完成用户角色、角色菜单授权。
- 普通管理员只能看到和调用已授权功能，越权 API 返回 403。
- 授权修改对现有会话下一次请求生效，不重启服务。
- 禁用用户和重置密码立即使旧会话失效。
- 超级管理员不能被停用、重置密码或修改角色。
- 并发修改返回 409，不覆盖其他管理员刚保存的数据。
- 密码和散列不进入 API、日志、审计摘要与测试快照。
- 后端集成测试、前端页面测试和全仓质量门禁通过。
