# mine-web 第三方软件声明

mine-web 以 [SoybeanAdmin](https://github.com/soybeanjs/soybean-admin) 为工程基线进行裁剪和二次开发。

| 项目 | 上游版本 | 上游提交 | 许可证 |
|---|---|---|---|
| SoybeanAdmin | v2.2.0 | `12f14672c4406f7b9d73be26b863d0dfed1edacc` | MIT |

保留内容：Vue 3/Vite/TypeScript 工程配置、Naive UI、UnoCSS、基础布局、主题、路由框架、请求封装、国际化、图标体系和内置错误页。

移除或替换内容：上游演示账号、Mock 后端、运行入口中的示例业务页面和上游权限模型。矿山登录、API 契约、动态菜单、业务页面和实时数据层由本项目自行实现；暂未引用的上游示例源码将在后续模块落地时继续裁剪。

上游 `LICENSE` 原样保存在本目录。依赖项的完整许可证清单在发行阶段通过 SBOM 和许可证扫描生成。
