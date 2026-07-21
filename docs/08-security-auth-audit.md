# 08 安全、认证、权限与审计

## 1. 安全目标

平台位于矿区生产网络，安全设计必须同时保护：

- 人员账号和管理 API。
- 设备身份、MQTT 主题和下行命令。
- 服务间账号、数据库、RabbitMQ 和 Redis。
- 协议、策略、原始报文和三维模型。
- 现场离线安装包、备份和升级包。

系统默认拒绝，按最小权限开放；任何“只在内网所以安全”的假设都不成立。

## 2. 网络安全域

~~~mermaid
flowchart LR
    DZ["设备网络区"] -->|"MQTTS/TCP白名单"| AZ["接入区"]
    UZ["办公/调度终端"] -->|"HTTPS/WSS"| AZ
    AZ --> APP["应用区"]
    APP --> DATA["数据区"]
    OPS["运维管理区"] --> AZ
    OPS --> APP
    OPS --> DATA
~~~

原则：

- 设备不能访问管理 API、数据库和内部 RabbitMQ vhost。
- 浏览器不能直接访问 RabbitMQ、Redis、PostgreSQL。
- 数据区端口只允许指定应用节点和运维跳板访问。
- RabbitMQ 管理端、Grafana 管理端和数据库管理端不暴露设备网。
- 五节点方案用防火墙白名单明确每个端口来源。

## 3. 管理员登录

使用 Sa-Token：

- 登录状态保存在 Redis，支持多应用节点共享。
- token 只通过 `X-Mine-Token` 请求头传递，不出现在 URL。
- 浏览器不把 token 写入可被任意脚本长期读取的明文日志。
- 登录失败限流；连续失败按账号和来源 IP 冷却。
- 支持主动注销、管理员踢下线和密码修改后失效旧会话。
- 配置 session timeout 和 active timeout。
- 后续可增加 TOTP，不在一期强制实现。

密码：

- 使用成熟 PasswordEncoder，优先 Argon2id；现场环境不满足时使用经过评估成本参数的 BCrypt。
- 禁止可逆加密保存用户密码。
- 初始超级管理员密码通过 secret 注入，首次登录强制修改。
- 日志、异常和审计不得记录密码/token。

## 4. RBAC

采用若依风格的五表简化模型：

~~~text
sys_user
sys_role
sys_menu
sys_user_role
sys_role_menu
~~~

sys_menu 同时承载：

- DIRECTORY：目录。
- MENU：页面。
- BUTTON：按钮或操作点。

关键字段：

~~~text
id, parent_id, name, type,
path, component_key, permission,
icon, sort, visible, status
~~~

不创建 sys_permission 和 sys_menu_permission。角色授权树直接勾选目录、菜单和按钮。

## 5. Sa-Token 权限加载

StpInterface：

- getPermissionList(loginId)：根据用户角色关联 sys_menu.permission。
- getRoleList(loginId)：返回角色 code。
- 当前实现每次权限判断实时读取 PostgreSQL；角色或菜单授权保存后，已有会话下一次请求立即生效，不需要重启服务或重新登录。
- 禁用用户和重置临时密码会注销该用户旧会话；`AccountStatusGuard` 还会在每次业务请求前再次确认账号状态。

API 示例：

~~~java
/**
 * 发布设备配置包。
 *
 * @param request 发布请求
 * @return 发布结果
 */
@SaCheckPermission("strategy:package:publish")
@PostMapping("/api/v1/strategy-releases")
public ConfigReleaseResponse publish(@Valid @RequestBody ConfigPublishRequest request) {
    return service.publish(request);
}
~~~

前端按钮权限不能替代后端注解。

## 6. 权限码规范

格式：

~~~text
模块:资源:动作
~~~

动作优先使用：

- list、view、create、update、delete/archive。
- export、import。
- test、publish、rollback。
- execute、ack、close。

示例：

~~~text
system:user:view
system:user:create
system:user:update
system:user:assign-role
system:user:reset-password
system:role:view
system:role:create
system:role:update
system:role:delete
system:role:assign-menu
system:audit:list
device:credential:rotate
protocol:definition:test
protocol:definition:publish
strategy:package:rollback
command:instance:create
alarm:event:view
alarm:event:ack
alarm:event:close
operations:backup:restore
~~~

禁止使用模糊权限，如 admin:all，超级管理员机制除外。

当前系统管理权限节点由 Flyway 随版本发布。管理员可以在前台创建用户和角色、分配用户角色、勾选角色权限，但不能自行创建路由、`componentKey` 或权限码。这样可以防止现场配置出前端不存在的组件或后端未实现的权限。

## 7. 超级管理员

- 系统只预置一个超级管理员身份或可配置超级管理员 ID。
- 超级管理员跳过普通权限判断，但仍记录全部审计。
- 超级管理员不能被停用、重置密码或修改角色。
- 其他角色全部由管理员前台创建。
- 生产环境不提供公开注册。

## 8. 操作审计

sys_operation_log 至少记录：

- operatorId、用户名快照。
- module、action、permission。
- resourceType、resourceId。
- requestId、traceId、来源 IP、User-Agent。
- result、errorCode、耗时。
- before/after 摘要或差异引用。
- reason。
- occurredAt。

强制审计操作：

- 登录、退出、失败登录。
- 用户、角色、菜单和授权变化。
- 设备凭据创建、轮换、吊销。
- 协议/策略测试、发布和回滚。
- 命令创建、取消和人工状态处置。
- 告警确认、关闭和人工修改。
- 数据导出、保留清理、备份和恢复。
- 三维模型和插件上传。

敏感字段在审计中脱敏；审计日志不作为异常堆栈垃圾桶。

当前访问管理已落地以下成功操作审计：`CREATE_USER`、`UPDATE_USER`、`ASSIGN_USER_ROLES`、`RESET_USER_PASSWORD`、`CREATE_ROLE`、`UPDATE_ROLE`、`DELETE_ROLE`、`ASSIGN_ROLE_MENUS`。密码重置摘要只记录 `passwordChangeRequired=true`，不记录明文密码、散列或令牌。

告警中心按查看、确认和关闭拆分 `alarm:event:view`、`alarm:event:ack`、`alarm:event:close`。确认和关闭由后端校验权限、必填说明、`Idempotency-Key`、状态和乐观版本；前端隐藏按钮只用于减少误操作。首次确认与首次关闭记录 `ALARM` 模块结构化审计，重复处置返回当前事实，不重复写确认记录或成功审计。审计摘要只保存必要状态与版本变化，不复制完整告警消息、证据或凭据。

## 9. 设备身份

MQTT 设备：

- 固定唯一 clientId。
- 每设备独立凭据，至少每产品模板生成不同随机 secret。
- RabbitMQ topic ACL 限制到本设备。
- 支持凭据轮换、双凭据短暂过渡和立即吊销。
- Last Will 用于异常离线辅助判断。
- 条件允许时使用客户端证书增强身份。

凭据展示：

- secret 创建后只展示一次。
- 平台业务库保存 credentialId、状态、时间和 broker 引用，不保存可直接取回的明文密码。
- 重新需要 secret 时执行轮换，不提供“查看原密码”。

TCP 设备：

- 使用设备登录帧、预共享密钥、IP 白名单或 TLS 证书组合。
- 不能仅用远端 IP 作为唯一身份。
- 登录前限制报文大小、速率和连接数。

## 10. 服务身份和密钥

- gateway、telemetry、server 使用不同 RabbitMQ 用户。
- 业务库、时序库使用不同数据库用户和 schema 权限。
- Redis 使用 ACL 用户。
- secret 不写入 Git、镜像、普通 .env 示例。
- Compose 使用 Docker secrets 或宿主机只读 secret 文件。
- 离线安装包不包含生产 secret，现场初始化生成。
- 证书和密钥有过期时间、轮换手册和告警。

## 11. 命令安全

命令模板定义危险等级：

| 等级 | 行为 |
|---|---|
| LOW | 普通确认，可按策略重试 |
| MEDIUM | 二次确认并填写原因 |
| HIGH | 专用权限、强确认、设备必须在线、不离线排队、不自动重试 |

平台不得通过普通文本框向设备发送任意二进制。所有生产命令必须来自已发布模板和 schema。

## 12. Web/API 安全

- HTTPS/WSS，禁用不安全 TLS 版本和弱密码套件。
- 严格 CORS 白名单，不使用任意 origin。
- Content-Security-Policy，三维资源域加入明确白名单。
- 上传校验扩展名、MIME、魔数、大小和路径。
- 下载使用资源 ID，不允许路径穿越。
- JSON、分页、查询时间范围设置大小上限。
- 导出任务异步生成并设权限、过期和审计。
- 防止 XSS：富文本和厂商字符串默认转义。
- Sa-Token 登录 API、协议调试、命令下发分别限流。

## 13. 供应链

- Maven、pnpm 和容器镜像全部锁定版本。
- 生成 SBOM。
- 构建执行依赖许可证和已知漏洞扫描。
- 禁止构建时从不受控镜像站拉 latest。
- 离线包包含镜像 digest、校验和、许可证清单和签名。
- Java 协议插件需要签名、哈希、来源和兼容版本记录。

## 14. 安全事件

安全相关告警：

- MQTT 认证失败激增。
- 同一设备 clientId 在异常位置并发登录。
- 管理员连续登录失败。
- 权限拒绝异常增多。
- 插件/配置包哈希不一致。
- 大量命令下发或危险命令失败。
- 备份加密/上传失败。
- 审计写入失败。

审计写入失败时，危险操作应拒绝执行；普通只读请求可继续。
