# 通知 Provider 配置与安全运行手册

## 1. 当前交付边界

当前版本提供数据库驱动的通知渠道控制面和七种随平台发行的内置 Provider：

| Provider | 渠道 | 非敏感配置 | 加密 secrets | 成功语义 |
|---|---|---|---|---|
| `smtp` | EMAIL | `host`、`port`、`securityMode`、`fromAddress`、`fromName`、`replyTo` | `username`、`password` | SMTP 服务受理，状态为 `SUBMITTED` |
| `wecom-robot` | ENTERPRISE_IM | 无 | `webhookUrl` | 企业微信机器人受理，状态为 `SUBMITTED` |
| `dingtalk-robot` | ENTERPRISE_IM | 无 | `webhookUrl`、可选 `signingSecret` | 钉钉机器人受理，状态为 `SUBMITTED` |
| `feishu-robot` | ENTERPRISE_IM | 无 | `webhookUrl`、可选 `signingSecret` | 飞书机器人受理，状态为 `SUBMITTED` |
| `generic-webhook` | WEBHOOK | `endpointUrl`、`allowedHosts`、可选 `signatureHeader` | 可选 `signingSecret`、`bearerToken` | HTTP 2xx，状态为 `SUBMITTED` |
| `aliyun-sms` | SMS | `endpoint`、`regionId`、`signName`、`templateCode` | `accessKeyId`、`accessKeySecret` | `SendSms` 返回 `OK` 并保存 `BizId`，状态为 `SUBMITTED` |
| `aliyun-voice` | VOICE | `endpoint`、`regionId`、`calledShowNumber`、`ttsCode`、`playTimes`、`volume`、`speed` | `accessKeyId`、`accessKeySecret` | `SingleCallByTts` 返回 `OK` 并保存 `CallId`，状态为 `SUBMITTED` |

平台不提供通知 JAR 上传、开放 SPI 注册、动态类加载或任意脚本执行。新增 Provider 必须随平台代码评审、测试和版本发布。

本批尚未实现升级策略发布、告警事务创建通知事件、投递租约工作器、异步回执、事件单和前端通知工作台。真实 SMTP、机器人、短信和电话发送按产品决策延期，自动化测试不会访问收费或外部 API。

## 2. 独立数据密钥

生产部署必须创建与人员数据密钥不同的通知密钥：

```bash
openssl rand -base64 32 > deploy/secrets/mine_notification_data_key
chmod 600 deploy/secrets/mine_notification_data_key
```

Compose 将其只读挂载为 `/run/secrets/mine_notification_data_key`，应用通过 `MINE_NOTIFICATION_DATA_KEY_FILE` 读取。密钥用于 AES-256-GCM，渠道标识、凭据用途和乐观锁版本共同参与 AAD；相同明文不会产生相同密文。

禁止把密钥写入环境变量、Git、镜像、数据库、日志或工单。密钥文件必须纳入现场受控备份。当前版本尚未提供在线密钥轮换作业，轮换前必须先实现并演练批量重加密；直接替换会导致历史渠道凭据无法解密。

## 3. 管理接口

- `GET /api/v1/notifications/providers`：返回受控 Provider 和字段元数据。
- `GET /api/v1/notifications/channels`：返回安全渠道摘要。
- `GET /api/v1/notifications/channels/{channelId}`：返回单个安全渠道摘要。
- `POST /api/v1/notifications/channels`：创建渠道。
- `PUT /api/v1/notifications/channels/{channelId}`：按 `version` 乐观更新渠道。

查看需要 `notification:channel:view`，修改需要 `notification:channel:edit`。请求中的 `secrets` 为只写字段；响应仅返回 `secretConfigured`，不得返回明文、密文或脱敏后仍可复原的内容。禁用渠道可以保存不完整草稿，启用前必须满足 Provider 必填配置。

## 4. 网络与重复发送边界

- 官方机器人只允许 HTTPS 官方域名；不跟随 HTTP 重定向。
- 通用 Webhook 必须配置精确 DNS 主机白名单，并阻断环回、链路本地、私网、共享地址和云元数据地址。
- 通用 Webhook 可对原始 JSON 正文生成 `HMAC-SHA256`，也可发送 Bearer 凭据；禁止换行控制字符进入请求头。
- 阿里云 SDK 关闭自动重试并有界复用客户端。没有获得可判定业务响应时返回 `UNKNOWN`，工作器后续不得盲目重发可能计费的短信或电话。
- SMTP 连接失败可重试；认证或收件地址错误为永久失败；发送阶段无法判断是否提交时返回 `UNKNOWN`。
- `SUBMITTED` 只代表供应商或邮件服务器受理，不代表最终终端送达。只有后续可信异步回执可以把短信或语音更新为 `DELIVERED`。

## 5. 上线前检查

```bash
bash deploy/tests/compose-config.sh
bash deploy/scripts/preflight.sh
```

现场启用任一 Provider 前还必须完成：供应商账号最小权限、IP/域名出口策略、签名与模板审核、号码资质、费用限额、退订与隐私要求、真实单条发送、回执对账、失败演练和密钥恢复演练。上述证据完成前，通知能力只能标记为非现场自动化通过，不能作为生产放行依据。
