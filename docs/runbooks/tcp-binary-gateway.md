# TCP 二进制 Gateway 运行手册

## 1. 适用范围与安全结论

本手册适用于 `mine-gateway` 的统一 TCP 二进制入口、遗留插件监听器和仓库示例 `haul-truck-binary`。监听器完成有界拆帧、
每连接插件会话、可信身份快照反查、RabbitMQ publisher confirm 后 ACK、连接限流、读空闲、TLS、健康和低基数指标。

`UNIFIED` 模式通过固定登录帧、BCrypt 凭据摘要和产品快照完成应用层认证及插件选择，并强制服务端 TLS；它不是挑战应答协议，仍应部署在受控设备网/专线/APN，并由防火墙、来源白名单或外部 mTLS 终止层补齐纵深防御。`LEGACY` 模式用于无法发送统一登录帧的旧设备，必须单独评估其厂商认证能力。未完成真实厂商报文、
证书、网络、容量、双节点和长稳门禁前，只能做非现场开发验收。

## 2. 不可破坏的运行不变量

1. 应用默认 `MINE_GATEWAY_TCP_ENABLED=false`；`UNIFIED` 必须启用 TLS 且不设置静态协议键，`LEGACY` 必须指定离线审核的 `protocolKey`。
2. 插件 JAR 在构建时进入不可变镜像，运行容器不接受网页上传、目录扫描或任意脚本。
3. `UNIFIED` 监听器按已认证产品动态选择插件；`LEGACY` 监听器只绑定一个插件。重复键、API 主版本不兼容或未知插件会阻止加载或启动。
4. 统一登录帧或遗留业务帧中的 `productKey + deviceCode` 只是候选身份，平台 `deviceId` 和 `protocolVersion` 必须来自完整身份快照。
5. 同一连接首个有效帧绑定设备，后续身份切换立即关闭。
6. RabbitMQ publisher confirm 成功前绝不返回应用 ACK；失败时设备按原 `bootId + sequence` 重传。
7. 任何 CRC、帧长、身份、连接或发布门禁都不能为了恢复流量临时放宽。
8. 指标和告警标签不得出现设备、IP、帧内容、异常文本、事件或追踪标识。

## 3. 启用前检查

在仓库根目录完成构建和静态部署校验：

~~~bash
./mvnw -pl mine-gateway -am package
jar tf mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar \
  | grep 'BOOT-INF/lib/mine-protocol-example-haul-truck-'
bash deploy/tests/compose-config.sh
docker compose --profile observability \
  --env-file deploy/env/standalone.env.example \
  -f deploy/compose/compose-standalone.yml config --quiet
~~~

启用前逐项确认：

- RabbitMQ 设备上行到平台遥测的发布账号和 publisher confirm 已正常；
- `deploy/config/gateway/device-identities.json` 或现场等价挂载是完整、递增版本快照；
- 设备映射使用插件实际版本 `haul-truck-binary:v1`；
- 宿主发布地址仍为 `127.0.0.1`，或已经完成设备网 ACL、路由和来源限制审批；
- 生产将 `MINE_GATEWAY_TCP_TLS_ENABLED=true`，证书 SAN 匹配设备访问名称，私钥未进入镜像、环境变量或日志；
- 选择 `UNIFIED` 时产品配置已绑定 TCP 插件和可信身份路由，身份快照只含 BCrypt `tcpSecretHash`，不含原始 secret；
- 端口、全局连接、单来源连接、读空闲、线程和 backlog 来自容量评审，而非直接采用最大值；
- Prometheus 已加载 `mine-gateway-tcp` 规则组，值班人知道五类告警的处置路径。

插件上线必须遵循“同一不可变制品、两处安装、先验证后放量”：

1. 将相同 `pluginKey + pluginVersion` 的审核 JAR 固化到 `mine-server` 和所有 `mine-gateway` 镜像，不接受前台上传 JAR。
2. 先启动管理服务；在产品协议页选择传输方式、插件键、插件版本和路由模式，保存二进制黄金样本并发布。
3. 管理服务未发现插件、版本不符或黄金样本执行失败时必须阻止发布。
4. 再滚动 Gateway，确认配置节点状态为 `LOADED` 且实际哈希一致；Gateway 缺插件或版本不符时保持 last-known-good，不得接收该产品流量。
5. 先放量测试设备，再逐批迁移。失败时回退上一镜像和配置包，不能只改数据库中的插件版本。

## 4. 身份快照

非生产示例可在完整快照中加入以下映射；`deviceId` 必须使用平台真实分配值，不能复制示例到生产：

~~~json
{
  "version": 2,
  "devices": [
    {
      "productKey": "haul-truck",
      "clientId": "tcp-truck-001",
      "deviceId": "019f63e2-071f-706a-bd12-4dad7150e75d",
      "deviceCode": "truck-001",
      "tenantScope": "mine-site-001",
      "protocolVersion": "haul-truck-binary:v1",
      "tcpUsername": "truck-001",
      "tcpSecretHash": "$2a$10$replace-with-a-real-bcrypt-hash"
    }
  ]
}
~~~

`tcpUsername` 和 `tcpSecretHash` 仅在 `UNIFIED` 模式必填；生产摘要必须通过受控凭据签发流程生成，禁止复制示例占位值。原始 secret 只交付设备，不进入数据库导出、快照、环境变量或日志。更新必须提交整个快照并递增 `version`。加载器按固定周期重新读取、校验摘要并原子替换索引；解析失败、重复
`productKey + deviceCode`、低版本或同版本异内容都不得覆盖当前快照。不要在运行容器内手工改文件。

## 5. Standalone 启动与模拟器验证

### 5.1 仅回环明文开发验证

保持示例环境中的以下边界：

~~~text
MINE_GATEWAY_TCP_PUBLISHED_ADDRESS=127.0.0.1
MINE_GATEWAY_TCP_PORT=9000
MINE_GATEWAY_TCP_MODE=LEGACY
MINE_GATEWAY_TCP_PROTOCOL_KEY=haul-truck-binary
MINE_GATEWAY_TCP_TLS_ENABLED=false
~~~

完成身份快照后启动依赖和 Gateway：

~~~bash
docker compose --env-file deploy/env/standalone.env.example \
  -f deploy/compose/compose-standalone.yml up -d \
  rabbitmq mine-server mine-gateway-cache-init mine-gateway mine-telemetry
docker compose --env-file deploy/env/standalone.env.example \
  -f deploy/compose/compose-standalone.yml ps mine-gateway
~~~

### 5.2 设备模拟器

~~~bash
./mvnw -pl mine-device-simulator-app -am package
java -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
~~~

浏览器打开 `http://127.0.0.1:18090`，新增 TCP Client：

| 字段 | 值 |
|---|---|
| 平台地址 | `127.0.0.1` |
| 平台 TCP 端口 | `9000` |
| TLS | 与 Gateway 配置一致；回环开发可关闭 |
| 报文格式 | `HEX` |
| 发送模式 | 先单次发送，确认后再做受控周期发送 |

复制 [矿卡 TCP 二进制示例](../../examples/protocols/tcp-binary/haul-truck/README.md) 中 51 字节黄金帧。预期结果：

- Gateway 反查到快照中的平台 `deviceId`，统一遥测包含 `engine.rpm=1350`、`vehicle.speed=12.5`、
  `vehicle.payload=45.6`；
- RabbitMQ 获得 publisher confirm 后，模拟器收到 13 字节 `0x81` ACK，序号为 42、结果码为 0、CRC 有效；
- `mine_gateway_tcp_frames{protocol="haul-truck-binary",result="success",reason="none"}` 增加；
- CRC 或身份错误时连接关闭、无 ACK，`result="rejected"` 按固定 `reason` 增加。

### 5.3 TLS 非现场验证

把 PEM 证书和私钥放到现场 secret 目录的 `tls.crt`、`tls.key`，设置 `MINE_GATEWAY_TCP_TLS_ENABLED=true` 后重启 Gateway。
模拟器 TCP Client 使用 JVM 默认信任库；自签名实验环境需把证书导入一个隔离信任库并通过 JVM 参数传入，不得修改操作系统或
生产全局信任库。生产必须使用受控 CA、证书到期告警和轮换记录。

### 5.4 统一入口验证

可改造设备使用以下部署边界：

~~~text
MINE_GATEWAY_TCP_MODE=UNIFIED
MINE_GATEWAY_TCP_PROTOCOL_KEY=
MINE_GATEWAY_TCP_TLS_ENABLED=true
~~~

设备建立 TLS 后先发送最大 512 字节的固定登录帧：`MNTL` 魔数、版本 `1`、2 字节总长度，以及分别带 2 字节长度前缀的 `productKey`、`deviceCode`、`username`、`secret`。Gateway 使用身份快照认证，再从已发布产品快照读取 `pluginKey + pluginVersion`，动态安装该插件的业务帧解码器。认证成功收到固定 6 字节登录确认后才能发送厂商业务帧；失败时连接关闭且不得根据响应区分账号不存在、密码错误或产品未发布。

## 6. 日常观察

~~~promql
max by (job, protocol) (mine_gateway_tcp_listener_up)
max by (job, protocol) (mine_gateway_tcp_connections_active)
sum by (protocol, result) (rate(mine_gateway_tcp_connections[5m]))
sum by (protocol, result, reason) (rate(mine_gateway_tcp_frames[5m]))
sum by (protocol) (increase(mine_gateway_tcp_publish_failures[15m]))
sum by (protocol) (increase(mine_gateway_tcp_acknowledgements[15m]))
~~~

`connections_active` 是容量观察，不是设备在线事实；连接关闭不等于设备离线策略已经收敛。日志只允许记录协议键、固定错误码和
异常类型，不要临时打开原始帧、secret、设备凭据或完整网络地址日志。

## 7. 告警处置

| 告警 | 首要检查 | 禁止操作 |
|---|---|---|
| `MineGatewayTcpListenerDown` | Gateway 进程、TCP health、插件加载、端口冲突、证书文件和启动日志 | 绕过插件兼容或证书校验强行启动 |
| `MineGatewayTcpConnectionsRejected` | 全局/单来源活动连接、代理是否丢失源地址、重连风暴和异常扫描 | 直接把连接上限调到最大值 |
| `MineGatewayTcpFramesRejected` | 固定 `reason`、厂商版本、CRC、设备时钟、身份快照和链路质量 | 关闭 CRC/帧长/身份校验或记录完整原始帧 |
| `MineGatewayTcpPublishFailures` | RabbitMQ 可用性、权限、交换机、网络和 publisher confirm 延迟 | 向设备补发伪造成功 ACK |
| `MineGatewayTcpNoSuccessfulTelemetry` | 矿区停产窗口、设备供电、网络、监听端口、协议版本和连续拒绝指标 | 未确认设备运行计划就静默告警 |

发布失败时先确认 Gateway 没有返回 ACK，再恢复 RabbitMQ 和权限；设备重连后按原序号重传，平台 Inbox 继续负责幂等。若无法证明
设备是否收到 ACK，不得由运维人工构造一条“等价遥测”。

## 8. 暂停、升级与回退

### 8.1 紧急暂停

1. 先在防火墙或负载均衡停止新连接，避免滚动期间旧实例继续接收。
2. 观察活动连接收敛；必要时通知设备按退避策略重连，不制造同时重连风暴。
3. 为全部 Gateway 实例设置 `MINE_GATEWAY_TCP_ENABLED=false` 并重新创建容器；Standalone 可执行
   `MINE_GATEWAY_TCP_ENABLED=false docker compose --env-file deploy/env/standalone.env.example -f deploy/compose/compose-standalone.yml up -d mine-gateway`；
   只替换部分实例不算暂停完成。
4. 保留指标、脱敏日志、身份快照版本、插件镜像 digest 和 RabbitMQ 证据，不清零计数。

### 8.2 兼容升级

协议键、`protocolVersion` 和 ACK 语义不变时：

1. 构建不可变镜像，执行 SDK、插件、Gateway、Compose 和 Prometheus 门禁。
2. 记录旧镜像 digest、插件版本、身份快照摘要和回退命令。
3. 先替换一个 Gateway，验证监听、成功帧、拒绝帧、发布确认和 ACK，再继续滚动。
4. 控制每批断开的连接数，观察重连和 RabbitMQ 积压。

### 8.3 不兼容升级

`protocolVersion`、帧或 ACK 不兼容时，先安装新插件到管理服务和全部 Gateway，使用黄金样本发布新的产品协议版本，再确认节点配置状态为 `LOADED`。统一入口可以在同一端口按不同产品并行迁移；同一产品仍是单一兼容边界，结构不兼容的设备应拆分产品。不可改造设备使用新协议键和独立遗留监听器/部署并行迁移。若只能在原遗留端口切换，必须申请维护窗口，同时停止旧实例，原子替换完整身份快照，再启动新镜像并验证。

### 8.4 回退

回退到上一不可变镜像、产品配置包和与其匹配的完整身份快照，恢复原入口模式、端口和 TLS secret 引用。Gateway 关闭会断开会话；未获得 ACK 的设备应按
原 `bootId + sequence` 重传。回退完成后验证 publisher confirm、ACK 和重复遥测幂等，不回滚数据库迁移、不删除遥测或审计证据。

## 9. 尚未完成的生产门禁

- 真实厂商协议文档、脱敏正常/边界/异常报文和命令 ACK；
- 原生 mTLS/设备证书身份或统一登录凭据签发、轮换、吊销和防暴力破解验证；
- 真实证书轮换、吊销、时钟偏差和密钥托管；
- 设备网 ACL、四层代理源地址、NAT、弱网、断网补传和重连风暴；
- 目标并发、报文频率、RabbitMQ confirm 延迟、CPU/内存/文件句柄和容量余量；
- 双 Gateway、负载均衡、滚动升级、断电、网络分区和故障恢复；
- 告警送达、值班响应、安全测试和 24/72 小时长稳。

以上项目完成并形成现场签字报告前，本手册和自动化结果不能作为生产上线批准或商业交付终验。
