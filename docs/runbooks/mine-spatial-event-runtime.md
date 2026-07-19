# 矿区路线、围栏与空间事件运行手册

## 1. 适用范围

本手册用于运行和排查 Phase 3 路线、途径点、电子围栏与空间事件闭环：版本化空间主数据、定位运行快照、
事件时间排序、边界消抖、进入/离开/停留状态机、可靠实时事件、固定水位查询和受控历史重放。

仓库内置确定性模拟定位，可用于开发、回归、演示和非现场自动化验收。自动化通过不替代真实 UWB 厂商接入、
矿区坐标标定、围栏测绘、事件阈值标定、容量、高可用、备份恢复、告警送达或 24/72 小时长稳验证。

## 2. 运行链路

~~~text
矿区管理 / 路线与围栏
  -> 途径点、路线、围栏稳定对象 + DRAFT 版本
  -> 发布不可变 PUBLISHED 版本
  -> SPATIAL_CHANGE 定位运行快照
  -> Telemetry 分页暂存、摘要校验、原子激活或保留 last-known-good

定位事实 location_point
  -> spatial_evaluation_queue
  -> 事件时间水位、允许迟到和空闲刷新
  -> 固定区域版本几何分类 + 连续确认点 + 单次停留状态机
  -> spatial_zone_state + spatial_event + spatial_event_outbox（同事务）
  -> mine.realtime.x / realtime.spatial.event.created v1
  -> mine-server 固定水位 REST 查询 + MINE_SITE_SPATIAL_EVENT WebSocket
  -> 矿区管理 / 路线与围栏、实时定位
~~~

途径点使用圆形区域，电子围栏使用多边形区域；路线是有序途径点版本，不直接产生第三种区域事件。空间事件始终引用
稳定区域标识、不可变发布版本、锚点位置事实、发生时标签/车辆/人员和运行修订，禁止按当前主数据回写历史。

## 3. 启用前检查

1. 使用单机 Compose 或等价部署，并确认以下开关已启用：
   `MINE_TELEMETRY_LOCATION_DISTRIBUTION_ENABLED=true`、
   `MINE_TELEMETRY_SPATIAL_ENABLED=true`、
   `MINE_TELEMETRY_QUERY_ENABLED=true`、`MINE_SPATIAL_REPLAY_ENABLED=true`、
   `MINE_REALTIME_ENABLED=true`、`MINE_REALTIME_RABBIT_ENABLED=true`。仓库单机 Compose 已显式启用查询、重放和定位分发，
   空间入队使用默认启用值；生产部署必须显式固化所有值。
   `MINE_TELEMETRY_LOCATION_DISTRIBUTION_ENABLED` 同时控制定位写入器、空间运行目录和评估队列；关闭会停止整个定位分发链路。
   `MINE_TELEMETRY_SPATIAL_ENABLED` 只控制评估、重放和空间 Outbox 后台任务，不控制位置事务内的空间入队：活动区域和定位流量
   仍存在时将持续形成 `PENDING` 积压，不能把该开关当成空间入队熔断器。
2. 确认管理 PostgreSQL 已迁移到 V41，TimescaleDB 已迁移到 V28；不要通过手工 DDL 绕过 Flyway。
3. 确认 `mine-server` 查询账号为最小权限 `mine_ts_reader`，重放登记账号为 `mine_ts_replay`，密码从只读 secret 文件加载。
   浏览器和普通管理账号不得获得两个时序库账号。
4. 在目标矿区完成来源坐标系、唯一活动统一坐标系、不可变转换版本、定位标签及发生时绑定，并验证定位运行快照已加载。
5. 为用户授予目标矿区数据范围和所需最小权限：查看 `mine:spatial:view`、编辑 `mine:spatial:edit`、发布
   `mine:spatial:publish`、事件 `mine:spatial:event:view`、重放 `mine:spatial:replay`。前端 `siteId` 不是授权凭据。
6. 先创建稳定对象，再创建草稿版本。发布前复核坐标系、有效时间、边界容差、进出连续确认点、停留阈值、允许迟到、
   空闲刷新、高度范围和引用顺序；发布版本不可原地修改。

## 4. 版本发布与变更

管理接口固定为：

~~~text
GET                /api/v1/mine/sites/{siteId}/spatial-regions/current
GET/POST           /api/v1/mine/sites/{siteId}/waypoints|routes|geofences
GET                /api/v1/mine/sites/{siteId}/{resource}/{id}
POST               /api/v1/mine/sites/{siteId}/{resource}/{id}/archive
GET/POST           /api/v1/mine/sites/{siteId}/{resource}/{id}/versions
GET/PATCH          /api/v1/mine/sites/{siteId}/{resource}/{id}/versions/{versionId}
POST               /api/v1/mine/sites/{siteId}/{resource}/{id}/versions/{versionId}/publish
~~~

其中 `{resource}` 只允许 `waypoints`、`routes`、`geofences`。列表状态只允许 `ACTIVE` 或 `ARCHIVED`，每页最多 100 条。
草稿修改必须携带当前乐观版本；发布会规范化子项顺序、计算 `sha256:` 内容摘要并捕获新的 `SPATIAL_CHANGE` 快照。

安全变更流程：

1. 从当前发布版本创建新草稿，不修改已发布行或顶点/路线引用。
2. 复核新旧有效时间，避免同一稳定对象的发布版本重叠。
3. 在非生产矿区用确定性场景验证边界和状态机参数。
4. 发布后观察快照加载阶段、评估队列、事件量和 Outbox；确认正常后再扩大流量。
5. 若新版本尚未生效，停止后续流量并归档错误稳定对象；若旧版本已预设有限 `validTo`，可创建从该时刻之后生效的更高版本。
   当前接口不允许修改已发布版本（包括 `validTo`），也不提供原地回滚/反归档：活动无限期版本发生错误时，应先归档稳定对象，
   再以新编码创建替代对象和正确版本。途径点仍被活动路线引用时，必须先处理引用，不能绕过约束强制归档。

## 5. 确定性模拟

模拟器启动与 MQTT 凭据、Topic、双坐标准备参见
[矿区定位运行与模拟验收手册](mine-location-runtime.md)。在 <http://127.0.0.1:18090> 的定位模板中选择下列场景：

| 场景 | 固定行为 | 预期结果 |
|---|---|---|
| `CROSS_BOUNDARY` | 12 步循环，`x=480` 在外、`x=520` 在内，`y=400,z=6` | 满足连续确认点后依次形成 `ENTER`、`EXIT` |
| `BOUNDARY_JITTER` | 在 `x=499.8` 与 `x=500.2` 之间交替 | 落入边界容差并保持稳定状态，不产生进出事件风暴 |
| `DWELL` | 120 步循环，第 4～90 帧保持 `x=520` | 先进入，在停留阈值到达后只形成一次 `DWELL`，离开后才允许下周期再次停留 |
| `LATE` | 使用跨界坐标，每第 5 帧的 `occurredAt` 回退 90 秒 | 在允许迟到窗口外固定记为 `too_late`，不得回滚已推进状态 |

这些预期依赖已发布区域与模拟几何一致。建议在统一坐标中让目标边界穿过 `x=500,y=400,z=6`：例如途径点圆心
`(520,400)`、半径 `20`，或电子围栏的一条边位于 `x=500`；边界容差至少覆盖 0.2 米。`DWELL` 以 1 秒周期在区域内
约持续 87 秒，演示阈值应小于该时长；`LATE` 的允许迟到应小于 90 秒。坐标转换、周期或版本参数不同，结果也会不同，
不得把场景名称当成事件成功的充分证据。

## 6. 事件与实时链路核查

具有 `mine:spatial:event:view` 和目标矿区范围的用户可使用：

~~~text
GET /api/v1/mine/sites/{siteId}/spatial-events
    ?from={ISO-8601}&to={ISO-8601}&zoneType=WAYPOINT|GEOFENCE
    &zoneId={zoneId}&subjectType=TAG|VEHICLE|PERSON&subjectId={subjectId}
    &eventType=ENTER|EXIT|DWELL&pageSize=100
GET /api/v1/mine/sites/{siteId}/spatial-events/{eventId}
WebSocket /api/v1/realtime：SUBSCRIBE MINE_SITE_SPATIAL_EVENT，ids=[{siteId}]
~~~

列表默认查询过去 24 小时，最长 31 天，每页 1～200 条。后续页必须复用首页 `watermark` 和服务端返回的不透明游标；
WebSocket 发生序号缺口时重新读取 REST，不得在浏览器内猜测事件。空间事件订阅与基础定位订阅独立降级。

时序库只读核查示例：

```sql
SELECT event.event_sequence, event.event_id, event.zone_type, event.zone_id,
       event.zone_version_id, event.tag_id, event.vehicle_id, event.person_id,
       event.event_type, event.previous_state, event.current_state,
       event.confirmation_points, queue.ingest_sequence,
       event.runtime_revision, event.occurred_at, event.evidence
FROM spatial_event event
JOIN spatial_evaluation_queue queue
  ON queue.location_event_id = event.anchor_location_event_id
WHERE event.site_id = :site_id
ORDER BY event.event_sequence DESC
LIMIT 50;

SELECT status, result_reason, COUNT(*)
FROM spatial_evaluation_queue
WHERE site_id = :site_id
GROUP BY status, result_reason
ORDER BY status, result_reason;

SELECT status, COUNT(*)
FROM spatial_event_outbox
GROUP BY status
ORDER BY status;
```

`evidence` 只允许有限统一坐标、`INSIDE/OUTSIDE/BOUNDARY/HEIGHT_UNKNOWN` 分类、`GOOD/DEGRADED` 质量、
可选固定原因和区域内容摘要。消息固定为 `realtime.spatial.event.created` schema v1，RabbitMQ 路由仅使用
`realtime.spatial.*` 低基数前缀。

## 7. 受控历史重放

只有明确需要按当前活动区域版本重算历史位置时才创建重放：

~~~text
POST /api/v1/mine/sites/{siteId}/spatial-events/replays
Idempotency-Key: {稳定且不超过 128 字符的键}

{
  "zoneType": "GEOFENCE",
  "zoneVersionId": "{当前活动不可变版本 UUID}",
  "from": "2026-07-19T00:00:00Z",
  "to": "2026-07-20T00:00:00Z",
  "reason": "经批准的历史空间事件补算"
}
~~~

时间范围按 `[from,to)` 解释，必须小于等于 31 天。接口只登记任务并返回 HTTP 202；请求线程不扫描历史。
相同矿区幂等键必须复用相同请求，载荷冲突会被拒绝；同一区域版本与重叠窗口存在未结束任务时也会被拒绝。

执行器在首次认领时冻结当前已激活区域快照、内容摘要、源摄入水位和数据库快照，使用独立
`spatial_replay_subject_state` 分批推进，不覆盖在线 `spatial_zone_state`。重放形成的正式事件仍受业务唯一键和同事务 Outbox
保护。运维核查：

```sql
SELECT request_id, zone_type, zone_version_id, window_start, window_end,
       status, attempts, scanned_points, emitted_events,
       zone_content_hash, created_at, started_at, completed_at, last_error
FROM spatial_replay_request
WHERE site_id = :site_id
ORDER BY created_at DESC
LIMIT 20;
```

不得通过直接更新重放状态、游标、冻结快照或独立状态表来“完成”任务。

## 8. 指标与告警

指标标签只允许固定 `stage/result/reason/zoneType/eventType`，禁止加入组织、矿区、区域、标签、车辆、人员、事件标识或异常文本。

| 指标 | 用途 |
|---|---|
| `mine_telemetry_spatial_queue_events_total` | 入队成功、重复、无活动区域和过晚跳过结果；入队异常不计入该计数器 |
| `mine_telemetry_spatial_stage_events_total` | 评估、状态恢复、Outbox 和重放阶段结果 |
| `mine_telemetry_spatial_events_total` | 进入、离开和停留事件计数 |
| `mine_telemetry_spatial_queue_backlog` | 等待或待重试评估记录数 |
| `mine_telemetry_spatial_outbox_dead` | 可靠发布死信数 |
| `mine_telemetry_spatial_replay_oldest_active_age_seconds` | 最老活动重放时长 |

默认告警：

- `MineSpatialEvaluationQueueBacklogHigh`：积压大于 1000 且持续 5 分钟，检查评估线程、数据库锁和区域复杂度。
- `MineSpatialTooLateRateHigh`：10 分钟至少 100 个样本且过晚比例大于 5%，持续 5 分钟后告警；检查设备时钟、网络和水位参数。
- `MineSpatialOutboxDead`：死信大于 0 且持续 1 分钟，按严重级别处置 RabbitMQ、发布确认和退避耗尽原因。
- `MineSpatialStateRecoveryFailures`：10 分钟出现状态恢复失败或死信，检查快照、队列租约、位置事实和事务。
- `MineSpatialReplayTimeout`：最老活动重放超过 30 分钟并持续 5 分钟，检查租约、查询计划、批量和失败退避。

## 9. 故障处置与安全回退

### 快照加载失败

继续使用最后正确运行修订，核对管理端当前版本、`spatial_runtime_snapshot`/条目摘要和加载阶段指标。修复草稿后发布更高版本，
不得删除最后正确快照或手工替换 Telemetry 运行修订。

### 评估积压或过晚升高

先停止模拟器或降低非生产发送频率，区分数据库性能、区域数量/复杂度和设备时钟/网络问题。不要通过放大迟到窗口掩盖时钟故障，
也不要清空队列或把 `SKIPPED/DEAD` 改回成功。

### Outbox 死信或实时中断

持久空间事件仍是事实源。检查 RabbitMQ 连接、发布确认、交换器绑定和脱敏错误，修复后按受审计流程恢复；禁止删除事件、
Outbox 或伪造已发布时间。前端应显示空间事件独立降级，基础定位不得被一并判死。

### 重放卡住或失败

检查最老活动时长、租约、源窗口查询计划、冻结摘要和 `last_error`。同一幂等键用于重试原意图；不要并发创建重叠任务，
不要让重放状态覆盖在线状态，也不要扩大已冻结源水位。

### 停止或回退空间事件

停止模拟流量，保留位置、队列、状态、事件和 Outbox 证据。需要撤回错误区域时归档稳定对象；只有旧版本已预设有限
`validTo` 时，才能发布从非重叠时刻开始的更高版本。当前不能修改已发布有效期或反归档，活动无限期版本需用新稳定对象替代。
当前没有“只停止空间入队、继续保存定位”的独立开关：需要止住新队列记录时，优先停止上游模拟/设备流量，或归档全部活动
途径点和围栏并等待新快照激活；后一方案会改变业务配置，途径点还受活动路线引用约束。关闭
`MINE_TELEMETRY_SPATIAL_ENABLED` 只会停止后台消费并让队列继续积压；关闭
`MINE_TELEMETRY_LOCATION_DISTRIBUTION_ENABLED` 会停止整个定位分发，影响范围更大。所有选择都必须记录窗口和积压，恢复后先
有界排空队列；因无活动区域而未入队的历史只能通过受控重放补算。禁止清库、修改历史或回滚 Flyway 迁移。

## 10. 生产放行前仍需完成

- 真实 UWB 厂商报文、时间源、频率、序号重启和异常样本适配。
- 现场控制点标定、围栏/途径点测绘、高度基准、边界容差和事件阈值验收。
- 目标标签数、区域数、峰值 Hz 和历史窗口下的评估、查询、实时和重放容量基线。
- Broker、Gateway、Telemetry、数据库、Redis 和 mine-server 的主备切换与故障注入。
- 空间主数据、运行快照、状态、事件、Outbox 和重放事实的备份恢复演练。
- 告警送达、值班处置、矿区弱网、时钟漂移以及连续 24/72 小时长稳。

以上现场项目状态均为 `DEFERRED`。证据完成前，只能宣称“仓库自动化空间事件闭环通过”，不能宣称已经取得矿区生产上线资格。
