# MQTT 设备模拟器

该模块用于 Phase 1 开发和自动化回归，不代表真实矿区设备验收。默认场景使用固定种子生成稳定遥测，并包含重复序号、
乱序补传和一次主动断线。模拟器只处理安全的 `QUERY_STATUS` 查询命令，其他命令回复 `REFUSED`。

`scenarios/weak-network.yaml` 用于弱网处理逻辑回归，包含 10 分钟主动 MQTT 优雅离线、重复报文、乱序补传、设备重启、序号归零，
以及重启后继续补传旧启动周期报文。自动化测试会注入虚拟等待器，因此不会真的等待 10 分钟；直接运行 JAR 时会按配置真实断开和等待。

该脚本不等价于真实弱网故障注入：Paho 会发送正常 `DISCONNECT`，不能覆盖骤断、半开连接、KeepAlive 超时、丢包、抖动和 QoS 在途重投。上述网络故障必须使用网络代理或现场网络完成 Task 16 验收。

## 构建

~~~bash
./mvnw -pl :mine-device-simulator package
~~~

## 准备

先通过平台 API 创建与场景一致的产品、设备并签发凭据。接口返回的 `username` 同时是 MQTT `clientId`
和主题中的设备身份；场景里的 `deviceCode` 是业务设备编码，两者不要混用。把仅返回一次的密码写入仓库外文件：

~~~bash
install -m 600 /dev/null /tmp/mine-simulator-password
read -rs MINE_SIMULATOR_PASSWORD
printf '%s' "${MINE_SIMULATOR_PASSWORD}" > /tmp/mine-simulator-password
unset MINE_SIMULATOR_PASSWORD
~~~

不得把密码写入 YAML、Git、命令行参数或 shell 历史。

把平台签发响应中的内部设备 UUID、`username` 和业务设备编码加入
`deploy/config/gateway/device-identities.json`。gateway 默认 5 秒内热加载，无需重启：

~~~json
{
  "version": 1,
  "devices": [
    {
      "productKey": "haul-truck",
      "clientId": "device-平台签发的标识",
      "deviceId": "019f63e2-071f-706a-bd12-4dad7150e75d",
      "deviceCode": "simulator-001",
      "tenantScope": "mine-site-001",
      "protocolVersion": "mqtt-json-v1"
    }
  ]
}
~~~

该文件是阶段 1 的现场兜底快照；后续由前台配置发布事件维护同一份运行时身份映射。

## 启动

受控内网明文 MQTT 示例：

~~~bash
java -jar examples/device-simulator/target/mine-device-simulator-0.1.0-SNAPSHOT.jar \
  --scenario=examples/device-simulator/scenarios/basic-mqtt.yaml \
  --broker=tcp://127.0.0.1:1883 \
  --username=device-<平台签发的标识> \
  --password-file=/tmp/mine-simulator-password
~~~

生产式验证应使用 `ssl://...:8883`。私有 CA 通过 JVM trustStore 配置，不允许关闭证书校验。

## 场景配置

| 字段 | 说明 |
|---|---|
| `sequenceOrder` | 报文实际发布顺序，可重复或乱序 |
| `bootOrder` | 与报文等长的启动周期标识；不配置时统一使用顶层 `bootId` |
| `occurredAtOffsetsMillis` | 与报文等长、相对 `startAt` 的发生时间偏移；用于表达补传原始时间 |
| `replayedIndexes` | 标记为断网补传的报文索引，避免不同启动周期使用相同序号时误判 |
| `disconnectBeforeIndexes` | 在指定报文索引前主动断开 MQTT |
| `disconnectMillis` | 每次主动断开的持续时间 |
| `restartBeforeIndexes` | 在指定报文索引前进入新启动周期并重新建立 MQTT 连接 |

所有索引从 0 开始，且必须落在 `sequenceOrder` 的有效范围内，越界配置会在启动时直接拒绝。
旧场景仍兼容 `replayedSequences`，新场景必须使用能够区分启动周期的 `replayedIndexes`。

弱网场景启动方式：

~~~bash
java -jar examples/device-simulator/target/mine-device-simulator-0.1.0-SNAPSHOT.jar \
  --scenario=examples/device-simulator/scenarios/weak-network.yaml \
  --broker=ssl://127.0.0.1:8883 \
  --username=device-<平台签发的标识> \
  --password-file=/tmp/mine-simulator-password
~~~

## 报文

上行遥测主题为 `mine/v1/{productKey}/{clientId}/up/telemetry`，报文中的 `deviceCode` 保留业务设备编码，
其余字段包括 `bootId`、`sequence`、`occurredAt`、`replayed` 和 `properties`。命令订阅与回复主题遵循
`docs/03-device-access-protocol.md`。
