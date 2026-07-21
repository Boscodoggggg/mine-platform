# S3 对象存储与 RustFS 开发测试设计

## 1. 目标与边界

平台使用标准 S3 API 承载三维场景资源，开发环境和场景资源存储集成测试使用 RustFS，但 Java 业务代码不得依赖 RustFS 专属 SDK 或管理接口。当前不新增 Maven 模块，而是在 `mine-server` 内建立可复用的对象存储基础设施包；只有当第二个运行进程确实需要复用实现时，再评估提取独立模块。

通用对象存储只负责对象写入、元数据查询、按范围读取、存在性判断和删除。GLB/3D Tiles 类型识别、恶意 ZIP 防护、内容摘要、资源清单、矿区权限和归档生命周期继续属于场景模块。数据库保存业务资源及文件清单，不把 RustFS 端点、凭据或厂商类型写入业务表。

## 2. 组件设计

在 `com.bosco.mine.server.infrastructure.storage` 下提供 `ObjectStorage` 端口、S3 实现、配置属性和读取结果类型。实现使用 AWS SDK for Java 2.x，通过 `endpoint`、`region`、`bucket`、访问凭据和 path-style 开关连接任意 S3 兼容服务。

一次应用部署只启用一种对象存储实现，因此不创建运行时 Factory 或策略注册表。Spring 配置依据 `mine.object-storage.type` 条件装配唯一的 `ObjectStorage` Bean。开发 Compose 设置为 `s3` 并将端点指向 RustFS；不依赖外部服务的纯单元测试可以直接构造内存或临时目录测试替身。

`SceneAssetStorage` 保留场景资源的安全校验和内容寻址职责，但不再向上层暴露宿主机 `Path`。上传先写入受控 staging 目录，完成大小、摘要、GLB 或 ZIP 清单校验后，将每个已验证文件写入 `scene/{sha256}/{relativePath}`。读取先依据数据库清单完成授权和路径校验，再通过 `ObjectStorage` 执行 HEAD 或单范围 GET。

## 3. 数据流与故障处理

上传过程先完成本地隔离校验，再上传不可变对象，最后在数据库事务中登记资源及文件清单。相同摘要和对象键允许幂等覆盖或复用。对象上传成功但数据库事务失败时会产生可安全回收的孤儿对象，继续沿用宽限期对账策略；不能在事务回滚时直接删除可能被并发请求复用的内容寻址对象。

下载保持现有权限边界和 HTTP 契约。S3 返回的流必须由响应生命周期关闭；Range 请求只接受单范围，返回准确的 `Content-Length`、`Content-Range`、`ETag` 和白名单 `Content-Type`。RustFS、网络或凭据故障统一转换为存储异常，不向客户端泄露 endpoint、bucket、object key 或密钥。

应用启动时校验配置完整性，并确保目标 bucket 存在；开发环境允许初始化容器创建 bucket，应用本身不使用 RustFS 管理 API。凭据只通过环境变量或只读 secret 注入，Compose 不把正式凭据写入仓库。

## 4. 测试与部署

对象存储契约测试覆盖 put、head、完整读取、Range、重复写入、缺失对象和错误凭据。场景资源集成测试使用 Testcontainers GenericContainer 启动固定版本 RustFS，真实验证 GLB 和 3D Tiles 上传、摘要幂等、文件清单读取及 Range 响应。包校验器继续使用本地临时目录单元测试，避免安全边界测试被网络噪声干扰。

开发 Compose 增加 RustFS 服务、命名数据卷、健康检查和 bucket 初始化，并让 `mine-server` 在健康检查通过后启动。RustFS 数据卷只用于开发环境；生产配置继续保持供应商中立，由部署方提供标准 S3 endpoint、bucket 和凭据。
