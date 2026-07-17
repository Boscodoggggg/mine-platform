# 设备模拟器新手向导 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将设备模拟器的单页技术表单改造成面向 IoT 新手的四步添加向导，默认引导 MQTT，同时保留 TCP Client 与进阶 TCP Server。

**Architecture:** 保留现有 Spring Boot API、YAML 配置模型和原生表单提交，只重组内置 HTML 表单并在 `app.js` 增加轻量向导状态。场景卡片继续写入原有 `transportMode` 字段，因此后端协议和存储结构无需变化。

**Tech Stack:** 原生 HTML、CSS、JavaScript、JUnit 5 静态交互契约测试、Chrome 浏览器验证。

---

### Task 1: 固化向导交互契约

**Files:**
- Modify: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/api/StaticInteractionContractTest.java`

1. 增加四个步骤、三种场景、上一步/下一步、最终确认区的静态契约。
2. 运行 `./mvnw -B -ntp -pl mine-device-simulator-app -Dtest=StaticInteractionContractTest test`，确认因向导尚未实现而失败。

### Task 2: 重组添加设备表单

**Files:**
- Modify: `mine-device-simulator-app/src/main/resources/static/index.html`
- Modify: `mine-device-simulator-app/src/main/resources/static/styles.css`

1. 增加四步进度条和场景选择卡片，默认 MQTT、TCP Server 标记“进阶”。
2. 将设备名称、产品标识和设备编码放入第二步。
3. 将连接参数、报文格式和模板放入第三步，并继续按协议渐进展示字段。
4. 增加第四步确认摘要和清晰的上一步、下一步、创建设备按钮。

### Task 3: 实现向导状态与校验

**Files:**
- Modify: `mine-device-simulator-app/src/main/resources/static/app.js`

1. 增加 `setWizardStep`，同步步骤内容、进度和按钮状态。
2. 增加 `validateWizardStep`，进入下一步前只校验当前步骤的必填字段。
3. 场景卡片更新原有 `transportMode`，继续触发默认参数和模板替换。
4. 增加 `renderWizardSummary`，在提交前以白话显示设备、连接方向和上报方式。

### Task 4: 验证与交付

**Files:**
- Test: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/api/StaticInteractionContractTest.java`

1. 运行静态契约测试并确认通过。
2. 运行模块全部测试，确认无协议和 API 回归。
3. 重新打包 JAR、重建 Docker。
4. 使用 Chrome 分别走通默认 MQTT 与 TCP Client 的步骤切换、字段隐藏和最终摘要。
