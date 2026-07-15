# MQTT 设备模拟器

该模块用于 Phase 1 开发和自动化回归，不代表真实矿区设备验收。默认场景使用固定种子生成稳定遥测，并包含重复序号、
乱序补传和一次主动断线。模拟器只处理安全的 `QUERY_STATUS` 查询命令，其他命令回复 `REFUSED`。

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

## 报文

上行遥测主题为 `mine/v1/{productKey}/{clientId}/up/telemetry`，报文中的 `deviceCode` 保留业务设备编码，
其余字段包括 `bootId`、`sequence`、`occurredAt`、`replayed` 和 `properties`。命令订阅与回复主题遵循
`docs/03-device-access-protocol.md`。
