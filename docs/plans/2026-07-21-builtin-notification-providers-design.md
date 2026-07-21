# 内置通知 Provider 与数据库配置设计

## 1. 决策

平台采用封闭 Provider 模式：客户只能使用平台随版本发布的通知实现，不能上传 JAR、注册任意 Provider 或执行自定义代码。删除独立 `mine-notification-sdk` Maven 模块，将适配器契约移动到 `mine-server` 的通知模块内部；未来只有在通知工作器拆成独立进程、官方适配器独立发版或商业版共享契约时再提取模块。

首批固定支持：

- `smtp`
- `wecom-robot`
- `dingtalk-robot`
- `feishu-robot`
- `generic-webhook`
- `aliyun-sms`
- `aliyun-voice`

Provider 编码来自编译期注册表。渠道管理 API 只接受注册表存在且渠道类型匹配的编码；数据库中的 `provider` 仍保存稳定编码，便于审计、查询和后续升级，但不能绕过注册表创建未知实现。

## 2. 配置与密文

`notification_channel.configuration` 保存非敏感 JSON，例如 SMTP 主机、端口、发件地址、安全模式、短信签名、官方 API 端点和通用 Webhook 主机允许列表。`notification_channel.secret_ciphertext` 保存整个 secrets JSON 的 AES-256-GCM 密文，例如 SMTP 用户名密码、机器人 Webhook URL 和签名密钥、阿里云 AccessKey。

API 请求允许提交明文 secrets，但响应只返回 `secretConfigured` 和 Provider 字段定义，不返回明文或密文。更新渠道时：

1. 使用旧渠道版本作为 AAD 解密已有 secrets；
2. 将请求中出现的 secret 字段覆盖到旧快照；
3. 校验 Provider 配置；
4. 使用新渠道版本作为 AAD 重新加密完整 secrets；
5. 通过乐观锁原子更新。

禁用渠道允许暂存不完整密钥；启用渠道必须具备全部必填配置和 secrets。审计仅记录 Provider、渠道类型、状态和配置字段名，不记录配置值、接收目标、密文或明文。

## 3. 运行边界

所有 Provider 实现都是 `mine-server` 内部 Spring Bean，由唯一注册表在启动期检查重复键。投递请求包含已渲染正文、接收目标、非敏感配置和仅在内存中存在的解密凭据。适配器不得访问 Mapper、Sa-Token、告警服务或业务数据库。

SMTP 通过 Jakarta Mail 发送，成功只表示 SMTP 服务器受理。企业微信、钉钉、飞书和通用 Webhook 通过 JDK HTTP Client 调用；官方机器人只允许各自 HTTPS 官方域名，通用 Webhook 必须命中数据库配置的主机允许列表，并拒绝环回、链路本地、私网和云元数据地址。钉钉与飞书按官方算法签名。

阿里云短信和语音使用官方 Java SDK，密钥只从解密后的内存快照传入。`SendSms` 成功保存 `BizId`，`SingleCallByTts` 成功保存 `CallId`，均返回 `SUBMITTED` 而非 `DELIVERED`。网络超时或无法判断供应商是否受理时返回 `UNKNOWN`，禁止工作器盲目重发计费请求。

## 4. 验证边界

自动化测试使用伪传输层和伪阿里云客户端，验证请求格式、签名、成功状态、错误分类、密文不出 API、Provider 白名单、数据库版本换密和注册表重复检测。真实 SMTP、机器人、短信与电话发送继续标记为延期验证，不在自动化中产生外部副作用。
