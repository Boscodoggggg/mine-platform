# 通知渠道管理工作台设计

## 1. 产品目标

管理员应当在“告警管理 → 通知渠道”中完成渠道创建、配置、启停和修改，不直接访问数据库。首批页面覆盖平台内置的 SMTP、企业微信、钉钉、飞书、通用 Webhook、阿里云短信和阿里云语音 Provider，并根据 Provider 元数据动态生成字段，避免前后端维护两套供应商能力清单。

页面采用桌面优先的工业控制台布局：主区域是中等密度渠道表格，编辑使用右侧抽屉，基础信息、Provider 配置、敏感凭据和超时控制按段组织。禁用状态允许保存草稿，启用时由后端执行完整 Provider 校验。页面只使用 Naive UI、UnoCSS、统一主题和 Vue I18n。

## 2. 敏感配置回显

密码、Webhook URL、签名密钥和 AccessKey 支持真实回显，但不并入普通渠道列表或详情响应。编辑已有渠道时，具备 `notification:channel:secret:view` 权限的用户通过独立接口读取明文，服务端在同一事务内解密并记录 `READ_NOTIFICATION_CHANNEL_SECRETS` 审计；审计只记录渠道、Provider、字段名和授权结果，不记录字段值、明文或密文。

敏感接口返回 `Cache-Control: no-store`，DTO 的 `toString()` 不包含 secrets。前端把返回值填入密码输入框，默认以密码形态展示，用户可点击眼睛查看真实值。没有敏感查看权限的编辑者不会调用该接口，提交时也不发送未加载的敏感字段，因此后端保留既有密文。主动清空已回显字段表示删除该凭据。

## 3. 路由与权限

数据库迁移在告警管理目录下发布 `/alarm/channels` 菜单，组件键固定为 `notification-channel-management`。现有 `notification:channel:view`、`notification:channel:edit` 权限移动到该菜单下，并新增 `notification:channel:secret:view` 隐藏按钮权限。`mine-admin` 的通配权限可以看到菜单和全部操作，普通角色仍由管理员按职责授权。

## 4. 错误与验证边界

列表、目录和编辑详情失败时保留可见错误与重试动作；保存使用服务端乐观锁版本，冲突要求刷新后重试。Provider 切换会重置不兼容配置和敏感字段。真实外部发送、收费 API 和最终送达仍按既定决策延期，本页面的“可用”指配置能够安全落库并被内置 Provider 消费，不宣称完成现场送达验收。
