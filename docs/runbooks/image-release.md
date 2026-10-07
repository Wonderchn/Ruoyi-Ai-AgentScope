# 镜像发布与固定版本

`release.yml` 在 main 或手动触发时复用完整 CI。涉及镜像与前端交付的 PR 会额外实际构建 web 镜像并启动容器；不在 PR 中登录注册表或发布。

发布顺序：

1. 完整 CI 和镜像契约通过。
2. 分别构建 platform、独立 AI 兼容服务、双前端，发布 `sha-<完整源码SHA>`；附 OCI revision/source、provenance 和 SBOM。
3. 检出源码后严格绑定三个 digest 与同一 SHA，生成 `release-manifest` 工件。缺失/额外镜像、可变标签、错误来源或迁移声明漂移均失败。
4. 使用空 `DOCKER_CONFIG` 匿名按 digest 拉取全部镜像，核对 OCI 标签；检查 platform 非 root、内嵌 profile/装配存在、旧模块未打包。
5. 启动 web 与合成平台上游，验证 `/`、`/admin/`、两个深链接、JS/CSS 内容类型、四个公开代理前缀及 internal/actuator 拒绝，生成 `image-verification` 工件。
6. 当前源码仍是 main 时才把三个已验证 digest 晋升为 `latest`，再次读取注册表验证 latest digest，生成 `image-promotion` 工件。期间 main 前进则标记 superseded，后续发布负责最新版本。

三个工件均在对应 GitHub Actions 发布运行页面下载。部署选定同一份 release manifest，使用其 `images` 值（`ghcr.io/wonderchn/...@sha256:...`），保留该 manifest 作为回退凭据。`latest` 用于发现已验证版本；不可替代不可变 digest。

内嵌部署使用 platform + web，并明确设置 `SPRING_PROFILES_ACTIVE=prod,embedded`，配置统一数据库、Redis、对象目录、身份与提供方。platform 镜像默认仍为 prod，入口不再把 profile 锁死。独立 ai 镜像保留给兼容模式，不需要与内嵌运行重复启动。web 依赖网络上的 `platform:6039`；管理端基路径 `/admin/`，公开 `VITE_CLIENT_ID` 在构建时配置且需与平台一致。

镜像资源/代理检查使用合成上游；业务身份、权限与执行行为由独立运行期 CI 及目标环境验收覆盖。发布成功不是自动部署，跨版本滚动升级和数据库降级未声明已验证。
