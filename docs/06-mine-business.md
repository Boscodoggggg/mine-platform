# 06 智慧矿山业务模型

## 1. 业务边界

平台不仅保存设备数据，还要把数据关联到“哪辆车、哪个司机、哪条路线、哪个班次和哪次作业”。设备是数据来源，车辆/人员/路线/作业才是矿山业务对象。

## 2. 核心对象

| 对象 | 说明 |
|---|---|
| Vehicle | 矿车、铲车等车辆档案 |
| Person | 人员基础信息 |
| DriverProfile | 驾驶资质、证件和有效期 |
| Device | 车载终端、传感器等物理设备 |
| LocationTag | UWB 标签 |
| Binding | 对象之间带生效时间的绑定 |
| Route | 路线及版本 |
| Waypoint | 装载点、卸载点、检查点等途径点 |
| Geofence | 二维/三维区域 |
| Shift | 班次定义和实例 |
| WorkOrder | 作业任务 |
| OperationRecord | 实际执行记录 |
| Command | 对设备的一次下行操作 |
| AlarmEvent | 可确认、可恢复的异常事实 |

## 3. 关系

~~~mermaid
erDiagram
    VEHICLE ||--o{ VEHICLE_DEVICE_BINDING : has
    DEVICE ||--o{ VEHICLE_DEVICE_BINDING : binds
    VEHICLE ||--o{ PERSON_VEHICLE_BINDING : operatedBy
    PERSON ||--o{ PERSON_VEHICLE_BINDING : drives
    VEHICLE ||--o{ TAG_BINDING : locatedBy
    LOCATION_TAG ||--o{ TAG_BINDING : assigned
    ROUTE ||--o{ ROUTE_WAYPOINT : contains
    WAYPOINT ||--o{ ROUTE_WAYPOINT : ordered
    WORK_ORDER ||--o{ OPERATION_RECORD : produces
    VEHICLE ||--o{ OPERATION_RECORD : executes
    PERSON ||--o{ OPERATION_RECORD : participates
    ROUTE ||--o{ OPERATION_RECORD : follows
~~~

所有绑定包含 valid_from、valid_to。历史点根据发生时间匹配当时绑定，不能用“当前绑定”回写过去。

## 4. 车辆

车辆档案建议包含：

- vehicleCode、名称、车牌/矿区编号。
- vehicleType、品牌、型号、额定载荷。
- status：ACTIVE、MAINTENANCE、OUT_OF_SERVICE、ARCHIVED。
- 所属区域/班组（可选）。
- 主要车载设备和定位标签由绑定表表示。

车辆归档不删除历史作业、告警、位置和设备绑定。

## 5. 人员和司机

Person 保存通用人员信息；DriverProfile 只保存驾驶相关信息，避免所有人员表都充满司机字段。

- personCode、姓名、联系方式（按权限脱敏）。
- employeeStatus。
- 驾驶证/资质类型、有效期和允许车型。
- 资质到期告警。

司机与车辆绑定可以按班次或明确时间段建立。一次作业开始时将 driverId、vehicleId 和当时姓名/编码快照写入 OperationRecord，防止档案后改影响历史展示。

## 6. 设备和业务对象绑定

绑定类型：

- VehicleDeviceBinding：车载终端/传感器到车辆。
- VehicleTagBinding：UWB 标签到车辆。
- PersonTagBinding：UWB 标签到人员。
- PersonVehicleBinding：司机到车辆。

规则：

- 同一标签同一时间只能绑定一个可定位对象。
- 同类主设备同一时间只能有一个主绑定。
- 新绑定不能与已有有效期重叠。
- 换车/换标签只关闭旧绑定并创建新绑定。
- 绑定操作写审计和业务事件。

## 7. 路线、途径点与围栏

Route 采用版本化：

- 路线名称、方向、适用车辆类型。
- 有序路径点/线段。
- 起点、终点和关键 Waypoint。
- 有效版本和坐标系。

Waypoint 类型可包括：

- LOAD：装载点。
- UNLOAD：卸载点。
- CHECK：检查点。
- WAIT：等待区。
- ENTRY/EXIT：入口/出口。

Geofence 定义区域和高度范围。位置事件采用进入、离开、持续停留状态机，使用迟到容忍和抖动消除，避免车辆在边界附近产生大量进出事件。

## 8. UWB

UWB 数据流：

~~~text
厂商定位消息或确定性模拟定位
→ Gateway 凭据/Topic 身份和固定 JSON v1 校验
→ Telemetry 最后正确矿区运行快照
→ 按 occurredAt 解析可信设备、标签、绑定与坐标转换版本
→ 原始坐标 + 矿区统一坐标的不可变历史事实和可靠实时 Outbox
→ Redis 当前位置顺序保护
→ REST 快照/轨迹 + WebSocket 增量
→ 实时监控/三维场景/围栏、途径点和作业事件
~~~

每个坐标点必须保留：

- 原始坐标系标识。
- 转换后的矿区统一坐标。
- 质量/置信度。
- 标签、绑定对象和转换版本。

同一矿区只允许一个活动的统一坐标系。来源坐标系和转换版本可按有效时间演进，历史点必须保留当时使用的
`sourceCoordinateSystemId`、`transformVersionId` 和发生时绑定，禁止用当前绑定或当前转换重写历史。
已建档但未绑定车辆/人员的活动标签允许形成标签级事实；未知、归档、未授权、非有限或越界观测进入有界隔离区。

低质量点可以写历史但不驱动危险告警，也不得覆盖较新的优质当前位置，具体由策略配置。重复启动周期序号必须幂等；
合法乱序点可保留历史但不得让当前位置倒退。实时页面采用“REST 快照后接 WebSocket 增量”，发现序号缺口必须重同步。

仓库设备模拟台提供固定圆形、折线、低质量、重复、乱序、未绑定标签和越界场景，并在模拟模式页面持久标识。
这些场景用于非现场自动化验收，不替代真实 UWB 厂商协议、矿区坐标标定、精度、容量、高可用、备份恢复和长稳验证；
运行和回退遵循 [矿区定位运行与模拟验收手册](runbooks/mine-location-runtime.md)。

## 9. 作业和运营记录

建议状态：

~~~mermaid
stateDiagram-v2
    [*] --> PLANNED
    PLANNED --> RUNNING
    RUNNING --> PAUSED
    PAUSED --> RUNNING
    RUNNING --> COMPLETED
    RUNNING --> ABORTED
    PLANNED --> CANCELLED
~~~

OperationRecord 包含：

- workOrderId、shiftInstanceId。
- vehicleId、driverId、routeVersionId。
- startAt、endAt、duration。
- start/end waypoint、作业单号、地点编码。
- 里程、趟数、深度、载重、油耗等可选指标。
- 完成状态和异常原因。
- 数据来源：自动推导、设备上报、人工修正。
- manualCorrectionReason 和审计信息。

自动识别依据围栏、途径点、设备事件和时间窗口。人工允许修正，但不能静默覆盖原始计算结果。

## 10. 实时监控

实时页面按选择的设备/车辆动态生成：

- 在线状态和最后数据时间。
- 发动机转速、温度、压力、油位等物模型属性。
- 车辆/人员位置、速度、方向和轨迹。
- 当前司机、路线、班次和作业。
- 活动告警。

设备字段不同时，页面从物模型显示 schema 生成卡片；不能为每种车复制一套 Vue 页面。

## 11. 命令业务

命令页面包含：

- 目标车辆/设备。
- 命令模板和参数。
- 权限与危险级别提示。
- 命令历史、状态时间线、回复内容。
- 编码使用的协议/配置版本。

“发送成功”应拆解显示：

- 已进入平台队列。
- 已发送到 Broker/连接。
- 设备已确认接收。
- 设备执行成功/拒绝/失败/超时。

## 12. 告警

告警不是一个脆弱的单向枚举。恢复事实和人工确认互相正交，因此值班态按下列方式推导：

~~~text
ACTIVE_UNACKNOWLEDGED ──确认──→ ACTIVE_ACKNOWLEDGED
          │ 恢复                         │ 恢复
          ▼                              ▼
RECOVERED_UNACKNOWLEDGED ──确认──→ RECOVERED_ACKNOWLEDGED
          └────────────── 关闭（仅已恢复）──────────────→ CLOSED
~~~

规则：

- 同一设备和稳定规则编码在一个活动周期只创建一个 UUIDv7 告警周期；Telemetry 持久状态与 Outbox 唯一约束共同避免重复风暴。
- 数值阈值和数据质量命中是首批受控来源，恢复条件、触发/恢复持续时间、迟滞和冷却分别配置。
- 只有通过持久最新值顺序判定的实时帧驱动状态；迟到补传、旧 boot 和低序号只保留历史，不触发或恢复实时告警。
- 告警保存触发/恢复证据、规则名称与等级快照、策略运行修订、快照标识和内容哈希，后续策略修改不篡改历史周期。
- 告警确认记录首次操作人、时间和必填说明；恢复前后都可以确认，重复请求幂等返回当前结果且不重复审计。
- 关闭只能发生在恢复之后，要求权限、说明、幂等键和乐观版本；活动风险不能通过人工关闭被隐藏。
- 规则删除、禁用或语义改变会以明确原因恢复旧活动周期，之后新语义按新周期计算。
- 当前闭环不包含短信、电话、企业 IM、工单升级或自动值班编排；这些通知渠道必须在独立交付中验证送达和升级策略。

## 13. 三维场景

平台负责：

- 上传和登记 GLB/3D Tiles 等外部模型。
- 场景版本、坐标原点、旋转、缩放和图层。
- 实时车辆、人员、告警、轨迹和围栏叠加。
- 模型缺失时使用演示模型或二维/三维网格降级。

平台不负责：

- DWG/IFC 编辑。
- 地质建模。
- 自动生成精细矿坑、巷道或设备模型。

Cesium 只在进入三维路由时懒加载，不能增加所有管理页面首屏体积。

## 14. 关键不变量

- 历史业务记录永远使用发生时的绑定和配置版本。
- 已完成作业不能因车辆/人员改名而丢失身份。
- 命令状态只能按允许的状态迁移，不能任意改 status 字段。
- 同一对象同类绑定的有效期不能重叠。
- 位置点质量不足时不能驱动高风险控制动作。
- 自动计算结果允许人工纠正，但保留原值、理由和审计。
