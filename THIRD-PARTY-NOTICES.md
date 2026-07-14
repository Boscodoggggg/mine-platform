# 第三方软件声明

本文件记录项目直接采用的第三方软件。传递依赖和发行镜像中的完整清单将在构建阶段通过 SBOM 与许可证扫描生成，发行包不得只依赖本文件作为完整清单。

| 软件 | 版本 | 用途 | 许可证 |
|---|---:|---|---|
| Spring Boot | 4.1.0 | Maven 依赖管理与后续服务运行底座 | Apache-2.0 |
| ArchUnit | 1.4.1 | 模块依赖边界测试 | Apache-2.0 |
| Jackson Databind | 3.1.4 | 统一消息 JSON 序列化与反序列化 | Apache-2.0 |
| Jackson Annotations | 2.21 | 消息契约兼容性与值对象注解 | Apache-2.0 |
| uuid-creator | 6.1.1 | UUIDv7 标识生成 | MIT，见 `licenses/uuid-creator-LICENSE.txt` |
| Flyway | 12.4.0 | PostgreSQL 数据库版本迁移 | Apache-2.0 |
| PostgreSQL JDBC Driver | 42.7.11 | PostgreSQL 数据库连接 | BSD-2-Clause |
| Testcontainers | 2.0.5 | 使用真实 PostgreSQL 进行集成测试 | MIT |

项目自身采用 Apache License 2.0。第三方软件的版权和许可证不因被本项目引用而发生改变。
