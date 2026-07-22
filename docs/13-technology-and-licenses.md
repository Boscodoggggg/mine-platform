# 13 技术版本、许可证与升级策略

## 1. 版本策略

本表是 2026-07-14 的设计验证基线。实施时：

- Maven 由父 BOM 和 dependencyManagement 锁定。
- pnpm 使用 pnpm-lock.yaml。
- 容器镜像使用完整 tag + digest。
- 禁止 latest。
- patch 安全更新可在回归后升级；major/minor 升级必须单独 ADR。

## 2. 后端基线

| 技术 | 设计基线 | 选择说明 |
|---|---|---|
| Java | 21 LTS | 保守运行基线；Spring Boot 4.1 支持，Java 25 待兼容验证 |
| Spring Boot | 4.1.0 | 当前稳定线，统一管理 Spring 依赖 |
| Maven | 3.9.x | Wrapper 固定实际版本 |
| Sa-Token | 1.45.0 | 官方版本增加 Spring Boot 4 starter |
| MyBatis-Plus | 3.5.13 或更高已验证 patch | 使用 spring-boot4-starter |
| Flyway | Spring Boot BOM 管理的兼容版本 | 禁止自动 ddl |
| Netty | Spring Boot BOM/项目 BOM 管理 | gateway 单独做兼容和压力测试 |
| JUnit/Testcontainers | BOM 锁定 | 真实中间件集成测试 |
| Eclipse Paho Java Client | 1.2.5 | 仅用于真实 MQTT 3.1.1 集成测试 |

Java 21 不是因为框架不能运行在 Java 25，而是一期减少 JDK、驱动、插件和现场运维变量。完成全套兼容和性能测试后可升级。

## 3. 前端基线

以 SoybeanAdmin 2.2.0 当前 package.json 为初始参考：

| 技术 | 基线 |
|---|---|
| Node.js | 24.18.0 LTS；SoybeanAdmin 本身要求 ≥20.19 |
| pnpm | ≥10.5，Corepack/离线缓存固定实际版本 |
| SoybeanAdmin | 2.2.0 作为初始代码基线 |
| Vue | 3.5.34 |
| Vite | 8.0.12 |
| TypeScript | 6.0.3 |
| Naive UI | 2.44.1 |
| Pinia | 3.0.4 |
| ECharts | 6.0.0 |
| CesiumJS | 1.141 |

克隆 SoybeanAdmin 后要保留原 MIT LICENSE 和 NOTICE 信息，并记录基线 commit，而不只记录版本字符串。

CesiumJS 1.141.0 使用 Apache-2.0。项目只加载随前端制品发布的 Cesium Workers、Assets、Widgets 和 ThirdParty，不使用 Cesium ion
token 或运行时公网模型。仓库确定性演示 GLB 由项目脚本从自制几何生成，标记为 CC0-1.0 并固定 SHA-256；客户提供的
GLB/3D Tiles、纹理、地形、影像和 CAD 转换产物仍须逐项确认来源、再分发权和 NOTICE，不能因平台采用 Apache-2.0 而默认获得授权。

## 4. 数据与消息基线

| 技术 | 基线 | 说明 |
|---|---|---|
| RabbitMQ | 4.1.8 | 启用 rabbitmq_mqtt、management、prometheus |
| PostgreSQL | 18.4 | 业务库；当前受支持版本 |
| TimescaleDB | PostgreSQL 18.4 + TimescaleDB 2.28.2 OSS 镜像线 | 使用 all-oss/Apache 2 构建并锁 digest |
| PostGIS | 随已验证 TimescaleDB OSS 镜像或独立兼容版本 | 坐标和空间查询 |
| Redis | 8.8.x | 选择 AGPLv3 许可路径并履行分发义务 |
| S3 接口 | 自研适配接口 | 实现可替换，不把业务绑定单一对象存储 |

TimescaleDB 必须使用 OSS/Apache 2 构建，例如官方镜像 tag 中带 oss，并在数据库设置和启动检查中验证许可模式。核心功能不能依赖 TSL 特性。

Redis 8 提供 AGPLv3 选项。平台将 Redis 作为独立进程使用，不修改其源码，但发布包仍必须携带相应许可证、源码获取方式和 NOTICE。若项目未来要求所有基础组件均为宽松许可证，可评估协议兼容的 Valkey；这不改变缓存接口，但属于部署基线变更，需要 ADR。

## 5. 运维基线

| 技术 | 用途 | 许可证关注 |
|---|---|---|
| Docker Engine/Compose | 容器运行与编排 | 现场发行方式需审查 Docker 产品条款 |
| Nginx | HTTPS/WSS 入口 | BSD 类 |
| Prometheus | 指标 | Apache 2.0 |
| Alertmanager | 基础设施告警 | Apache 2.0 |
| Grafana | 仪表板 | AGPLv3 |
| Loki | 日志 | AGPLv3 |
| Grafana Alloy | 日志/指标采集 | Apache 2.0，实际版本复核 |

AGPL 组件作为独立服务部署不代表可以忽略许可证。发行包必须保存原许可证、变更说明和源码获取方式；如对外商业发行，发布前由法律/合规人员复核。

## 6. 开源许可证

项目自身许可证正式确定为 Apache License 2.0，SPDX 标识为 Apache-2.0。选择原因：

- 允许商业使用和私有化部署。
- 有明确专利授权条款。
- 与主要 Java/前端基础库兼容性较好。
- 适合作为平台型开源项目。

根目录已经建立 LICENSE 和 NOTICE。正式发布前还必须完成：

- 根 LICENSE。
- NOTICE。
- THIRD-PARTY-NOTICES。
- 前端模板原始许可证。
- 容器内第三方组件许可证。
- 协议插件和示例的许可证边界。

项目源码默认按 Apache-2.0 发布；第三方组件仍遵循各自许可证，不能用项目许可证覆盖第三方义务。

## 7. 依赖准入

新增依赖必须说明：

- 为什么标准库/现有依赖不能解决。
- 许可证。
- 维护活跃度和安全记录。
- 传递依赖数量。
- 是否支持离线构建。
- 是否影响镜像体积和启动时间。
- 替换成本。

禁止：

- 无许可证仓库。
- 仅个人临时发布且无人维护的核心依赖。
- 为一个小工具函数引入庞大依赖。
- 生产依赖 SNAPSHOT、RC 或 nightly。

## 8. 升级节奏

- 每周：漏洞和 CVE 监视。
- 每月：patch 版本评估和测试环境升级。
- 每季度：完整依赖、镜像和许可证审查。
- 每个现场版本：冻结 BOM、lockfile 和 image digest。
- major 升级：兼容矩阵、迁移、性能、回滚和 ADR。

RabbitMQ、PostgreSQL、TimescaleDB 的升级顺序遵循各自官方支持路径，不跨越未支持的 major 路径。

## 9. 构建产物

标准 Maven 与前端构建先生成便于直接拿取的扁平发行制品：

~~~text
release/
├── mine-server.jar
├── mine-gateway.jar
├── mine-telemetry.jar
└── mine-web.zip
~~~

`./mvnw package` 自动输出三个 Spring Boot 可执行 JAR，`pnpm --dir mine-web build` 自动把 `dist/` 内容压缩为根层含
`index.html` 的 `mine-web.zip`，不需要额外手工打包命令。包含镜像、SBOM、许可证、配置、脚本、摘要和 Release Notes 的正式
离线交付包仍由发布门禁生成，不能用上述四个应用制品替代。正式发行清单必须记录前端 lockfile、Cesium 版本/许可证、Cesium
静态目录摘要、演示资源生成器版本和 SHA-256。客户模型默认不进入公开发行包；确需交付时必须在客户授权边界内单独列出来源、
许可、摘要、坐标版本和适用矿区。

## 10. 官方资料

- Spring Boot 系统要求：https://docs.spring.io/spring-boot/system-requirements.html
- Apache License 2.0：https://www.apache.org/licenses/LICENSE-2.0
- Spring Boot 项目页：https://spring.io/projects/spring-boot/
- Sa-Token releases：https://github.com/dromara/Sa-Token/releases
- MyBatis Spring Boot：https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/
- MyBatis-Plus：https://github.com/baomidou/mybatis-plus
- RabbitMQ MQTT：https://www.rabbitmq.com/docs/4.1/mqtt
- RabbitMQ 安装版本：https://www.rabbitmq.com/docs/4.1/download
- Eclipse Paho Java Client：https://eclipse.dev/paho/clients/java/
- PostgreSQL 版本策略：https://www.postgresql.org/support/versioning/
- TimescaleDB releases：https://github.com/timescale/timescaledb/releases
- TimescaleDB OSS 镜像：https://hub.docker.com/r/timescale/timescaledb-ha/tags
- TimescaleDB 许可证模式：https://docs.timescale.com/about/latest/timescaledb-editions/
- Redis 许可证：https://redis.io/legal/licenses/
- Node.js 版本策略：https://nodejs.org/en/about/previous-releases
- SoybeanAdmin：https://github.com/soybeanjs/soybean-admin
- CesiumJS：https://github.com/CesiumGS/cesium

## 11. 版本锁定前验证清单

- Spring Boot、Sa-Token、MyBatis-Plus Boot 4 starter 能同时启动。
- Jackson 3 序列化与 Sa-Token/消息 DTO 兼容。
- RabbitMQ MQTT 设备会话、vhost 和 topic ACL 通过。
- Timescale OSS 镜像确实包含指定 PostgreSQL、Timescale 和 PostGIS。
- Redis 许可文件进入发行包。
- SoybeanAdmin 基线构建在离线 Node 镜像中成功。
- Cesium 无 ion 加载本地确定性 demo，并通过真实 WebGL 浏览器回归。
- 非三维路由不加载 Cesium chunk/Workers/Assets/Widgets/ThirdParty，WebGL 不可用时二维仍可运行。
- 客户模型、GPU/浏览器矩阵、共享 S3、容量和长稳在生产版本锁定前形成现场证据。
- 所有镜像存在 amd64 版本；如现场使用 arm64，单独验证多架构。
