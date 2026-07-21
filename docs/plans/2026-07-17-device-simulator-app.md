# 独立设备模拟器实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付一个单 JAR、内置 Web 管理页的独立矿山设备模拟系统，支持 MQTT/MQTTS、TCP Client/Server、JSON/HEX 报文和运行控制。

**Architecture:** 新建独立 Maven 模块 `mine-device-simulator-app`，Spring Boot 同时托管静态页面与本地 REST API。配置保存到用户目录 YAML，密码只通过启动请求进入内存；运行时根据传输模式创建 MQTT、TCP Client 或 TCP Server 会话。

**Tech Stack:** Java 21、Spring Boot 4.1 Web MVC、Eclipse Paho MQTT、SnakeYAML、原生 HTML/CSS/JavaScript、JUnit 5、MockMvc。

---

### Task 1: 建立独立可执行模块

**Files:**
- Modify: `pom.xml`
- Create: `mine-device-simulator-app/pom.xml`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/SimulatorApplication.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/SimulatorApplicationTest.java`

1. 先编写 Spring 上下文失败测试并确认模块尚不存在。
2. 加入 Reactor 模块、Web MVC、Paho、SnakeYAML 依赖和启动类。
3. 运行 `./mvnw -pl mine-device-simulator-app -am test` 确认上下文通过。

### Task 2: 实现 YAML 配置模型和存储

**Files:**
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/profile/SimulatorProfile.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/profile/ProfileStore.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/profile/YamlProfileStore.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/profile/YamlProfileStoreTest.java`

1. 先测试 MQTT、TCP Client、TCP Server 配置往返保存，以及密码不落盘。
2. 实现不可变配置、稳定 ID、字段校验和原子文件替换。
3. 验证损坏 YAML、重复 ID 和非法端口会明确失败。

### Task 3: 实现 JSON/HEX 报文渲染

**Files:**
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/payload/PayloadRenderer.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/payload/PayloadRendererTest.java`

1. 测试 `${sequence}`、`${timestamp}`、`${productKey}`、`${deviceCode}` 占位符。
2. 测试带空格 HEX 解码及奇数长度、非法字符拒绝。
3. 实现无脚本执行的确定性渲染器。

### Task 4: 实现 MQTT 与 TCP 传输会话

**Files:**
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/transport/SimulatorTransport.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/transport/MqttSimulatorTransport.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/transport/TcpClientSimulatorTransport.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/transport/TcpServerSimulatorTransport.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/transport/TcpTransportTest.java`

1. 用本机随机端口先测试 TCP Client 发送和 TCP Server 接受后发送。
2. 实现连接、发送、断开、重连、关闭和状态查询。
3. TLS 使用 JVM trustStore/keyStore，不提供跳过证书校验开关。

### Task 5: 实现模拟任务与运行 API

**Files:**
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/runtime/SimulatorRuntimeService.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/runtime/SimulatorSnapshot.java`
- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/web/SimulatorController.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/runtime/SimulatorRuntimeServiceTest.java`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/web/SimulatorControllerTest.java`

1. 先测试启动、定时发送、单帧、断开、重连、停止和统计。
2. 使用有界日志环形队列，禁止记录密码。
3. 提供 profile CRUD、action 和 snapshot REST API，统一输出可读错误。

### Task 6: 实现内置工业试验台页面

**Files:**
- Create: `mine-device-simulator-app/src/main/resources/static/index.html`
- Create: `mine-device-simulator-app/src/main/resources/static/app.js`
- Create: `mine-device-simulator-app/src/main/resources/static/styles.css`
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/web/StaticPageTest.java`

1. 先测试首页、核心表单和控制按钮可访问。
2. 实现桌面优先、暖橙矿石灰、三栏工业试验台：配置列表、编辑区、运行状态/日志。
3. 根据传输模式渐进展示 MQTT 或 TCP 字段，所有运行操作给出明确反馈。

### Task 7: 文档、打包与完整验证

**Files:**
- Create: `mine-device-simulator-app/README.md`
- Modify: `README.md`

1. 记录启动命令、YAML 位置、TLS 信任库、安全边界和平台 TCP 尚未实现的限制。
2. 执行模块全部测试、Reactor 打包、JAR 启动和 HTTP 冒烟。
3. 核对只修改本功能文件，不纳入工作区其他改动。

## 传输模式表单引导补充设计

设备模拟器面向需要快速验证接入链路的开发和实施人员，连接表单继续保持单页、低学习成本的工具型体验。切换传输模式时，界面同步改变主机和端口的业务名称：MQTT 使用“Broker 地址 / Broker 端口”，TCP 客户端使用“平台地址 / 平台 TCP 端口”，TCP 服务端使用“监听地址 / 监听端口”。每次切换都自动恢复该模式推荐的主机、端口、报文格式和报文模板，避免旧模式参数被误用于新连接。

连接参数中容易产生歧义的字段增加紧邻标签的帮助按钮。帮助内容只解释字段用途、参数来源和典型值，不重复字段名称；帮助按钮支持鼠标悬停、键盘聚焦和点击聚焦，且通过无障碍标签向读屏软件提供相同信息。MQTT 专属的 TLS、Client ID、用户名和发布 Topic 只在 MQTT 模式出现，TCP 模式保持精简。所有提示沿用当前冷白蓝紫主题，不引入新的弹窗或页面层级。
