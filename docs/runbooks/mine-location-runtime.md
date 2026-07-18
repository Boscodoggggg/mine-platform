# 矿区定位运行与模拟验收手册

## 1. 适用范围

本手册用于运行和排查 Phase 3 矿区定位纵向闭环：资产与时间绑定、版本化双坐标、固定 MQTT JSON v1
定位接入、TimescaleDB 历史事实、Redis 当前位置、可靠实时事件、WebSocket 和二维定位工作台。

仓库内置模拟器可重复产生正常和异常路线，用于开发、回归、演示和非现场验收。自动化通过不替代真实 UWB
厂商协议、矿区坐标标定、现场精度、目标频率与容量、主备切换、备份恢复或 24/72 小时长稳验收。

## 2. 运行链路

~~~text
设备模拟器或 UWB 网关
  -> /mine-device vhost 的 mine/v1/{productKey}/{clientId}/up/location
  -> Gateway 凭据快照、Topic 身份和固定 JSON v1 校验
  -> mine.telemetry.x / location.observation
  -> Telemetry 最后正确定位快照
  -> 按 occurredAt 解析设备、标签、资产绑定和坐标转换版本
  -> location_point + location_inbox + location_realtime_outbox（同事务）
  -> Redis 当前位置顺序保护 + mine.realtime.x
  -> mine-server REST 快照/轨迹 + WebSocket 增量
  -> 矿区管理 / 实时定位
~~~

控制面快照是按矿区发布的不可变完整包。Telemetry 先分页拉取、暂存并校验数量和摘要，再原子切换运行修订；
加载失败继续使用最后正确版本。原始坐标、统一坐标、转换版本和发生时绑定同时固化到位置事实，禁止事后按当前绑定改写历史。

## 3. 启用前检查

1. 使用单机 Compose 或等价部署，并确认以下开关已启用：
   `MINE_TELEMETRY_LOCATION_DISTRIBUTION_ENABLED=true`、`MINE_TELEMETRY_QUERY_ENABLED=true`、
   `MINE_REALTIME_ENABLED=true`、`MINE_REALTIME_RABBIT_ENABLED=true`。仓库单机 Compose 已显式启用。
2. 在“矿区管理”创建组织和矿区，为操作用户授予对应矿区数据范围。
3. 创建车辆、定位标签和接入设备；接入设备必须已有活动凭据快照。
4. 创建来源坐标系。模拟器模板默认使用 `uwb-local`，必须与控制面 `systemCode` 完全一致，或者在模板中改为现场编码。
5. 创建唯一活动的矿区统一坐标系，再创建、校验并发布从来源坐标系到统一坐标系的不可变转换版本。
6. 使用换绑预览和幂等执行接口建立发生时有效的车辆—设备绑定、车辆/人员—标签绑定。绑定区间按
   `[validFrom, validTo)` 解释，定位事实只按 `occurredAt` 解析。
7. 等待定位快照成功加载，再开始发送数据。不要通过重启规避错误快照；应修复控制面后发布更高修订。

模拟“已建档但未绑定对象”时，先在目标矿区创建活动标签 `SIM-UNBOUND-718`，不要给它建立车辆或人员绑定。
`718` 来自仓库模拟器固定种子；若以后调整种子，以模拟器实际生成的 `tagCode` 为准。未预建该标签时，平台会按
`UNKNOWN_TAG` 隔离，这测试的是“未知标签”，不是“未绑定标签”。

## 4. 启动模拟定位

在仓库根目录启动模拟器：

```bash
./mvnw -pl mine-device-simulator-app -am package
java -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
```

打开 <http://127.0.0.1:18090>，新建设备并选择 MQTT：

- Broker 指向设备 vhost 的 MQTT 入口，用户名和一次性密码使用平台生成的设备凭据。
- `productKey`、`deviceCode`、`clientId` 必须与已激活凭据快照一致。
- Topic 使用 `mine/v1/{productKey}/{clientId}/up/location`，QoS 推荐 1，周期先使用 1000 ms。
- 在报文步骤选择定位场景并应用模板，检查 `coordinateSystemCode` 是否与来源坐标系一致。
- 先连接，再单次发送一帧；确认链路正常后才启动周期发送。

需要在前端永久展示“模拟数据”标识时，以模拟模式启动或构建前端：

```bash
VITE_LOCATION_SIMULATION=true pnpm --dir mine-web dev
```

模拟标识仅用于防止演示数据被误认为生产数据，不会放宽 Gateway 身份、协议、边界或作用域校验。

## 5. 确定性场景与预期

| 场景 | 行为 | 预期结果 |
|---|---|---|
| `CIRCLE` | 固定种子的圆形路线 | 历史事实、当前位置和实时页面连续推进 |
| `POLYLINE` | 固定矿区折线路线 | 转弯处速度和航向由相邻点计算，轨迹可重复 |
| `LOW_QUALITY` | `accuracyMeters=50` | 写入降级历史，不覆盖较新的优质当前位置，不驱动高风险业务判定 |
| `DUPLICATE` | 每 5 帧复用上一帧序号、时间和坐标 | Inbox 幂等命中，不产生第二条历史和实时副作用 |
| `OUT_OF_ORDER` | 每 5 帧回退两个序号和发生时间 | 合法迟到点可保留历史，Redis 当前位置不得倒退 |
| `UNBOUND_TAG` | 使用固定已建档、未绑定对象标签 | 保留标签级事实，车辆和人员为空；未建档时按 `UNKNOWN_TAG` 隔离 |
| `OUT_OF_BOUNDS` | 生成明显越界坐标 | 按 `OUT_OF_BOUNDS` 进入有界隔离区，不写位置历史 |

切换场景会写入不同的 `bootId` 后缀，避免上一场景的序号身份污染新场景。不要把异常场景长期留在连接真实告警通知的环境。

## 6. 验证数据面

使用具有 `mine:location:view` 和目标矿区范围的用户验证：

```text
GET /api/v1/mine/sites/{siteId}/locations/current?limit=5000
GET /api/v1/mine/sites/{siteId}/locations/trajectory
    ?subjectType=TAG&subjectId={tagId}&from={ISO-8601}&to={ISO-8601}&maxPoints=5000
WebSocket /api/v1/realtime：SUBSCRIBE LOCATION，scopeId={siteId}
```

轨迹查询窗口最多 24 小时，后续页必须复用首页 `watermark` 和服务端返回的游标。首次订阅先读取 REST 快照，
再接收 WebSocket 增量；发现序号缺口时必须重新同步，不能在浏览器内猜测缺失位置。

只读数据库核查示例：

```sql
SELECT site_id, tag_id, vehicle_id, person_id, time,
       source_x, source_y, source_z, local_x, local_y, local_z,
       transform_version_id, binding_type, binding_id,
       quality_status, quality_reason, ingest_sequence
FROM location_point
WHERE site_id = :site_id
ORDER BY ingest_sequence DESC
LIMIT 20;

SELECT reason_code, COUNT(*)
FROM location_quarantine
GROUP BY reason_code
ORDER BY reason_code;

SELECT status, COUNT(*)
FROM location_realtime_outbox
GROUP BY status
ORDER BY status;
```

禁止在生产查询中跳过矿区作用域，也禁止为了“修正显示”直接修改位置事实、Inbox、Outbox 或快照表。

## 7. 指标与告警

定位指标仅使用固定阶段、结果、原因和算法标签，禁止加入组织、矿区、设备、标签、车辆、人员、坐标系、异常文本等高基数标签。

| 指标 | 固定标签 | 用途 |
|---|---|---|
| `mine_gateway_location_ingest_total` | `result`, `reason` | Gateway 接受、拒绝和重新投递 |
| `mine_telemetry_location_snapshot_stage_total` | `stage`, `result` | 快照拉取、暂存、校验、激活和恢复阶段 |
| `mine_telemetry_location_snapshot_result_total` | `outcome` | 快照最终加载结论 |
| `mine_telemetry_location_ingest_total` | `result`, `reason` | 事实存储、重复、隔离和故障 |
| `mine_telemetry_location_transform_total` | `algorithm`, `result` | 固定坐标转换算法执行结果 |
| `mine_telemetry_location_redis_latest_total` | `result` | 当前位置更新、顺序忽略和失败 |
| `mine_telemetry_location_quarantine_size` | 无 | 当前隔离区记录数 |

默认告警：

- `MineLocationSnapshotFailuresPersistent`：保留最后正确版本，核对服务令牌、控制面快照分页、摘要和数据库。
- `MineLocationQueueBacklogHigh`：检查 `mine.location.q`、Telemetry 消费线程、TimescaleDB 写入延迟和磁盘。
- `MineLocationIngressRejectionRateHigh`：按固定 `reason` 检查凭据快照、Topic、产品/设备身份和 JSON v1 契约。
- `MineLocationRedisLatestFailuresPersistent`：历史事实仍可提交；恢复 Redis 后确认有界缓存重建和水位推进。
- `MineLocationQuarantineNearLimit`：80,000 是默认运维处置阈值，不是数据库硬容量；按原因止损，并按批准流程清理已到期证据。当前版本不会自动物理删除隔离记录。

## 8. 故障处置与安全回退

### 快照失败

保留当前运行修订，比较 `location_runtime_revision` 与控制面最新修订；检查失败阶段指标和脱敏日志。修复控制面数据后通过正常业务写操作发布更高修订，不删除最后正确快照。

### Redis 故障

不要回滚已经提交的历史事实或 Outbox。恢复 Redis 后观察 `updated/ignored/failure` 指标，并确认
`location_current_rebuild_watermark` 前进。重建有界，热点标签优先；大矿区必须依据现场容量调整批次。

### 队列或 Outbox 积压

先停止模拟器周期发送，保留单帧诊断能力；检查 RabbitMQ、数据库、消费者和发布确认。不要清空队列、Inbox 或
Outbox。定位实时 Outbox 采用租约和退避重试，达到上限的 `DEAD` 记录必须保留并按根因处置。

### 停止模拟验收

停止模拟器任务并断开连接，撤销或轮换专用模拟设备凭据；保留验收事实用于审计，按数据保留策略处理。
若前端使用模拟构建，生产发布必须重新以 `VITE_LOCATION_SIMULATION=false` 构建。不要为了停模拟而关闭整个生产定位链路。

## 9. 生产放行前仍需完成

- 真实 UWB 厂商主题、协议、时间源、频率、重启序号和错误报文适配。
- 矿区控制点标定、转换版本复核、全场边界和二维/三维精度验收。
- 目标设备数与峰值上报率下的 Gateway、RabbitMQ、TimescaleDB、Redis、REST 和 WebSocket 容量测试。
- Broker、Gateway、Telemetry、数据库、Redis 和实时服务的主备切换与故障注入。
- 定位历史、快照、Outbox 和缓存恢复相关的备份恢复演练。
- 真实网络弱网、断链补传、时钟漂移及连续 24/72 小时长稳。

这些项目未形成现场证据前，只能宣称“仓库自动化定位纵向闭环通过”，不能宣称已经获得矿区生产上线资格。
