# 02 仓库与后端设计

## 1. 后端技术风格

mine-server 采用“模块优先 MVC + 局部领域模型”：

- Controller：HTTP 协议转换、参数校验、权限注解。
- Service：用例编排、事务、跨实体规则和事件产生。
- Model：实体、值对象和确实复杂的状态规则。
- Repository/Mapper：数据库访问。
- Event：模块对外声明的事实。

简单 CRUD 不强行创建聚合根、工厂和领域服务；命令状态、配置发布、绑定有效期等复杂规则则必须封装，不能散落在 Controller 和 SQL 中。

## 2. mine-server 目录

~~~text
mine-server/src/main/java/com/bosco/mine/
├── MineServerApplication.java
├── modules/
│   ├── system/
│   ├── device/
│   ├── vehicle/
│   ├── personnel/
│   ├── binding/
│   ├── route/
│   ├── location/
│   ├── operation/
│   ├── command/
│   ├── alarm/
│   ├── strategy/
│   └── scene/
└── bootstrap/
    ├── config/
    ├── security/
    ├── web/
    └── messaging/
~~~

每个业务模块使用一致结构：

~~~text
vehicle/
├── controller/
│   └── VehicleController.java
├── dto/
│   ├── VehicleCreateRequest.java
│   └── VehicleResponse.java
├── service/
│   ├── VehicleCommandService.java
│   └── VehicleQueryService.java
├── model/
│   ├── Vehicle.java
│   └── VehicleStatus.java
├── repository/
│   ├── VehicleRepository.java
│   ├── VehicleMapper.java
│   └── record/VehicleRecord.java
└── event/
    └── VehicleChangedEvent.java
~~~

Repository 接口与 MyBatis 实现可先放同一模块；只有出现多实现或测试隔离价值时再拆 infrastructure 子目录。

## 3. 业务模块清单

| 模块 | 核心职责 |
|---|---|
| system | 用户、角色、菜单、字典、登录日志、操作日志 |
| device | 产品、设备、凭据、分组、能力、在线状态 |
| vehicle | 车辆档案、车辆类型和生命周期 |
| personnel | 人员、司机资质和状态 |
| binding | 设备/标签/人员/车辆的带时间绑定 |
| route | 路线、途径点、围栏和版本 |
| location | UWB 标签、实时位置查询、轨迹和区域事件 |
| operation | 班次、作业单、运行记录和统计 |
| command | 命令模板、实例、尝试、回复和超时 |
| alarm | 告警事件、确认、恢复和通知状态 |
| strategy | 物模型、协议、映射、策略、配置包和发布 |
| scene | 三维资源、图层、坐标转换配置 |

## 4. 依赖方向

~~~mermaid
flowchart LR
    C["Controller"] --> S["Application Service"]
    S --> M["Model / Policy"]
    S --> R["Repository"]
    R --> DB["PostgreSQL"]
    S --> O["Outbox"]
    O --> MQ["RabbitMQ"]
~~~

- Controller 不直接调用 Mapper。
- Mapper 不返回给前端 DTO。
- 模块 A 不引用模块 B 的 Mapper、Record 或内部实现。
- 跨模块同步调用只依赖对方公开的 Facade/Query Service。
- 跨进程和非即时一致操作优先发布事件。

## 5. 事务与消息一致性

mine-server 的关键业务写操作使用 Transactional Outbox：

1. 业务表更新和 sys_outbox_event 插入处于同一 PostgreSQL 事务。
2. relay 按批读取未发布事件并发送 RabbitMQ。
3. Broker confirm 成功后标记已发布。
4. 发送失败指数退避，超过阈值进入告警，不删除记录。
5. 消费者使用 eventId 建立 inbox/幂等记录。

适用场景：

- 配置包发布。
- 命令实例创建。
- 设备、车辆和绑定关系变化。
- 告警状态变化。
- 作业记录完成。

gateway 的高频原始消息没有业务数据库事务，直接向 RabbitMQ 发布并使用 publisher confirm；telemetry 消费依靠手工 ACK 和业务幂等。

## 6. API 规范

- 路径前缀：/api/v1。
- 资源使用复数名词，例如 /api/v1/devices。
- 分页参数统一为 page、size、sort。
- 时间全部使用带时区 ISO-8601，存储为 UTC，界面按矿区时区展示。
- 成功响应可直接返回资源；错误必须使用统一 ProblemDetail 风格。
- 错误至少包含 code、message、traceId、details、timestamp。
- 校验错误 details 标明 field 和 reason。
- 发布、回滚、命令等非 CRUD 动作用显式子资源，例如 /strategy-releases。
- 所有写接口支持审计上下文；命令创建支持 Idempotency-Key。

建议错误码：

~~~text
AUTH-401-001     登录状态无效
AUTH-403-001     缺少接口权限
DEVICE-404-001   设备不存在
PROTOCOL-422-001 协议样本回归失败
STRATEGY-409-001 同优先级策略冲突
COMMAND-409-001 设备不支持该命令
TELEMETRY-422-001 遥测值不符合物模型
~~~

## 7. 持久化规范

- PostgreSQL 业务库由 Flyway 管理，禁止生产环境自动 ddl update。
- MyBatis-Plus 只用于常规 CRUD 和分页；复杂 SQL 使用明确 XML/注解 SQL，不堆叠难读 Wrapper。
- 表名按领域前缀区分，不按 Java 包自动生成。
- 主键使用 UUIDv7；业务编码另设唯一索引，不把可变编码当主键。
- 所有可变业务表包含 created_at、created_by、updated_at、updated_by、version。
- 乐观锁用于配置草稿、档案编辑和人工操作冲突。
- 历史和审计表禁止物理级联删除。
- 归档使用 status/archived_at；需要彻底清理时走有审计的保留任务。

## 8. Java 编码规范

根 AGENTS.md 要求所有后端 Java 代码使用中文 Javadoc。执行规则：

- public 类、接口、枚举、public/protected 方法必须有中文 Javadoc。
- Javadoc 解释职责、边界、参数、返回值、异常和幂等性，不重复方法名。
- 复杂私有算法补充“为什么”，不为显而易见代码写逐行注释。
- 协议、命令、幂等和时间规则必须在类型上可读，避免 magic number。
- DTO 使用不可变 record 或明确不可变类；实体与响应 DTO 分离。

示例：

~~~java
/**
 * 发布已经完成校验的设备配置包。
 *
 * <p>该操作只创建不可变发布记录和消息事件，不直接修改运行节点内存。
 * 重复提交相同幂等键时返回第一次发布结果。</p>
 *
 * @param command 发布命令，包含作用范围、配置版本和操作原因
 * @return 新生成的配置发布记录
 * @throws StrategyConflictException 设备组存在同优先级冲突时抛出
 */
ConfigRelease publish(ConfigPublishCommand command);
~~~

## 9. mine-common 边界

允许放入：

- 通用错误结构、分页类型、traceId、时钟抽象。
- UUIDv7、基础 JSON、哈希和非业务校验工具。
- 测试 fixture 的公共基类。

禁止放入：

- Device、Vehicle、Command 等业务对象。
- 通用 BaseService 无限继承体系。
- 任意模块 Mapper。
- 与某一厂商协议相关的常量。
- 为少写几行代码而建立的反射魔法。

## 10. 启动与关闭

应用启动必须按顺序：

1. 校验环境变量、secret 文件和外部依赖配置。
2. 初始化日志、指标和 traceId。
3. 校验数据库迁移状态。
4. 建立 RabbitMQ/Redis 连接。
5. 加载最后成功配置快照。
6. 注册消费者或开放 HTTP/TCP 端口。
7. 健康检查转为 ready。

优雅关闭时先拒绝新流量，再停止消费、等待在途任务、提交批次、关闭连接。超过超时才强制退出。

## 11. 后端测试目录

~~~text
src/test/java/
├── architecture/              # ArchUnit
├── unit/                      # 纯单元测试
├── slice/                     # WebMvc/MyBatis切片
└── integration/               # Testcontainers集成测试
~~~

数据库集成测试使用真实 PostgreSQL/TimescaleDB 容器，不用 H2 模拟 PostgreSQL 行为。
