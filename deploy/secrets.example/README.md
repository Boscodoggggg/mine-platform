# 单机部署 secret 清单

复制本目录说明到 `deploy/secrets/`，创建以下文件；每个文件只放一行值，不要提交真实内容：

- `postgres_password`：业务 PostgreSQL 密码。
- `timescale_password`：TimescaleDB 密码。
- `timescale_reader_password`：mine-server 查询 TimescaleDB 的独立只读账号密码。
- `timescale_replay_password`：mine-server 仅登记空间重放请求的独立最小权限账号密码。
- `redis_password`：Redis 密码，建议至少 32 个随机字符。
- `redis_exporter_passwords.json`：Redis Exporter 使用的 JSON 密码映射，`redis://redis:6379` 的值必须与 `redis_password` 一致。
- `rabbitmq_admin_username`：RabbitMQ 运维账号，仅使用字母、数字、点、下划线和连字符。
- `rabbitmq_admin_password`：RabbitMQ 运维密码，使用十六进制随机值，避免配置语法字符。
- `rabbitmq_gateway_device_password`：gateway 读取 `/mine-device` 上行队列的独立密码。
- `rabbitmq_gateway_platform_password`：gateway 写入 `/mine-platform` 遥测交换机的独立密码。
- `rabbitmq_telemetry_password`：telemetry 消费遥测与策略队列、发布实时和告警业务事件的独立密码。
- `rabbitmq_server_realtime_password`：mine-server 创建本实例临时队列并消费实时事件的独立密码。
- `rabbitmq_server_command_password`：mine-server 只写命令交换机的独立密码，不授予队列读取权限。
- `rabbitmq_server_alarm_password`：mine-server 只读告警事实队列的独立密码，不授予交换机写入权限。
- `rabbitmq_server_operation_password`：mine-server 只读运营空间事实队列的独立密码，不授予交换机写入权限。
- `rabbitmq_server_operation_outbox_password`：mine-server 只写运营实时事件交换机的独立密码，不授予队列读取权限。
- `mine_configuration_distribution_token`：mine-server 与运行节点之间拉取配置快照、回报加载状态的共享服务令牌，至少 32 个随机字符。
- `mine_super_admin_password`：平台首次启动超级管理员密码，12–72 UTF-8 字节。
- `mine_person_data_key`：人员手机号和证件号码 AES-256-GCM 加密密钥，仅保存 `openssl rand -base64 32` 生成的一行 Base64 内容；丢失后历史敏感字段无法恢复。
- `grafana_admin_password`：Grafana 运维管理员密码，建议至少 24 个随机字符。
- `tls.crt`：包含完整证书链的 PEM 证书。
- `tls.key`：未加密 PEM 私钥，宿主机权限必须为 0600。

普通密码可用 `openssl rand -base64 36` 生成；RabbitMQ 运维密码使用 `openssl rand -hex 36`。证书私钥和密码文件必须通过现场受控介质交付。

`redis_exporter_passwords.json` 使用以下结构，不要把示例占位符原样用于生产：

```json
{
  "redis://redis:6379": "与 redis_password 一致的密码"
}
```
