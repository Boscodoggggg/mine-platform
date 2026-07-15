# 单机部署 secret 清单

复制本目录说明到 `deploy/secrets/`，创建以下文件；每个文件只放一行值，不要提交真实内容：

- `postgres_password`：业务 PostgreSQL 密码。
- `timescale_password`：TimescaleDB 密码。
- `timescale_reader_password`：mine-server 查询 TimescaleDB 的独立只读账号密码。
- `redis_password`：Redis 密码，建议至少 32 个随机字符。
- `rabbitmq_admin_username`：RabbitMQ 运维账号，仅使用字母、数字、点、下划线和连字符。
- `rabbitmq_admin_password`：RabbitMQ 运维密码，使用十六进制随机值，避免配置语法字符。
- `rabbitmq_gateway_device_password`：gateway 读取 `/mine-device` 上行队列的独立密码。
- `rabbitmq_gateway_platform_password`：gateway 写入 `/mine-platform` 遥测交换机的独立密码。
- `rabbitmq_telemetry_password`：telemetry 只读消费 `/mine-platform` 遥测队列的独立密码。
- `mine_super_admin_password`：平台首次启动超级管理员密码，12–72 UTF-8 字节。
- `grafana_admin_password`：Grafana 运维管理员密码，建议至少 24 个随机字符。
- `tls.crt`：包含完整证书链的 PEM 证书。
- `tls.key`：未加密 PEM 私钥，宿主机权限必须为 0600。

普通密码可用 `openssl rand -base64 36` 生成；RabbitMQ 运维密码使用 `openssl rand -hex 36`。证书私钥和密码文件必须通过现场受控介质交付。
