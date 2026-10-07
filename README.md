# Ruoyi-Ai-AgentScope

多租户 AI 平台：若依负责身份、租户、角色和业务管理，AgentScope 负责受控 Agent 执行，RAG 提供知识摄取与检索。当前主线采用 **platform 内嵌 AI 运行时 + 统一 PostgreSQL 迁移链 + 双前端工作区**。

已实现的核心包括身份与租户边界、知识摄取/检索、run 状态与幂等、Worker/outbox/SSE 重放、受控 Agent 和平台管理端。集成 CI 覆盖构建、权限、数据库迁移与合成运行期回归。部署仍需配置数据库、Redis、模型提供方、租户套餐和角色权限，并在目标环境验收；这不等于所有上游功能都已迁移。

## 架构与目录

| 路径 | 用途 |
| --- | --- |
| `services/platform` | Java 17 / Spring Boot 3 平台；`ruoyi-ai-*` 模块承载当前内嵌 AI 能力 |
| `services/web/apps/workbench` | Vue 3 用户工作台，镜像入口 `/` |
| `services/web/apps/admin` | Vue 3 管理端，镜像入口 `/admin/` |
| `services/web/packages/events` | run/SSE/RAG 协议与传输 |
| `services/web/packages/platform-client` | 共享身份、请求、权限与错误处理 |
| `services/ai` | Java 17 / Spring Boot 4 独立 AI 服务，保留兼容模式及冻结迁移、跨服务回归 |
| `infra/docker` | platform、AI 兼容服务和双前端的三个镜像构建定义 |
| `scripts/ci` | 数据库、运行期、发布清单和镜像交付验证 |

未装配的旧 `ruoyi-chat`、`ruoyi-aiflow`、`ruoyi-generator` 模块已退出源码与 Maven reactor。仍在使用的 `ruoyi-ai-*`、`com.nageoffer.ai.ragent.*`、业务 `ruoyi-workflow` 和原有迁移历史继续保留。原上游来源与许可证见 [upstreams.lock.json](docs/upstreams.lock.json)。

## 构建与验证

需要 JDK 17、Maven、Node.js >=22.13.0 和 pnpm 11.0.9。前端只使用工作区根的一份锁文件。

```bash
# 内嵌平台：完整 reactor 验证
cd services/platform
mvn -B -ntp -Pdev clean verify

# 双前端与共享包
cd ../web
pnpm install --frozen-lockfile
pnpm test
pnpm typecheck
pnpm lint
pnpm build

# 独立 AI 兼容模式
cd ../ai
mvn -B -ntp -Pci clean verify
```

需要外部组件或模型服务的测试范围见 [external-tests.md](docs/runbooks/external-tests.md)。PR 必须通过现有 `ci-required` 门禁；涉及镜像交付的 PR 还要通过实际容器内的双前端、资源文件与代理边界检查。

## 运行与数据库

内嵌运行需明确选择 `dev,embedded` 或 `prod,embedded` profile，例如：

```bash
SPRING_PROFILES_ACTIVE=prod,embedded java -jar services/platform/ruoyi-admin/target/ruoyi-admin.jar
```

这是 profile 选择示例，数据库/Redis、提供方和身份配置需先按运行环境注入。`embedded` profile 显式装配 P2/P3；未选择该 profile 的基础配置不自动启用这些能力。Skills 激活保持关闭，外部沙箱、真实模型外发和审批需要各自的配置与授权。管理目录需要平台管理身份及对应 scope；只赋权限字符串不能替代管理身份，菜单还需进入租户套餐。

统一迁移链以 `services/platform/docs/script/sql/postgres` 和独立的 [unified-schema-contract.json](scripts/ci/unified-schema-contract.json) 为准。版本并不连续；不要自行填补编号或重新执行另一条历史链。CI 同时验证统一库新装、从 platform V6 + AI V12 升级、重复迁移和漂移拒绝，继续保留旧链检查。既有迁移不可改写。

配置入口见 [configuration.md](docs/configuration.md)；运行、兼容模式及回退见 [p2-p3-dev.md](docs/runbooks/p2-p3-dev.md)。真实凭据只通过环境注入。应用示例配置见 `services/web/apps/workbench/.env.example`，前端 `VITE_CLIENT_ID` 是公开客户端标识，必须与平台 `sys_client` 配置一致。

## GitHub 容器镜像

| 镜像 | 用途 |
| --- | --- |
| [ruoyi-ai-agentscope-platform](https://github.com/Wonderchn/Ruoyi-Ai-AgentScope/pkgs/container/ruoyi-ai-agentscope-platform) | 当前平台；内嵌部署设置 `SPRING_PROFILES_ACTIVE=prod,embedded` |
| [ruoyi-ai-agentscope-web](https://github.com/Wonderchn/Ruoyi-Ai-AgentScope/pkgs/container/ruoyi-ai-agentscope-web) | 工作台 `/` 与管理端 `/admin/`，同源代理到 `platform:6039` |
| [ruoyi-ai-agentscope-ai](https://github.com/Wonderchn/Ruoyi-Ai-AgentScope/pkgs/container/ruoyi-ai-agentscope-ai) | 独立 AI 兼容部署 |

main 验证通过后发布 `ghcr.io/wonderchn/<镜像名>:sha-<完整提交SHA>`。release manifest 将三个不可变 digest、源码 SHA 和 [兼容性声明](docs/release-compatibility.json) 绑定；干净 runner 匿名拉取并验证来源标签、内嵌装配和双前端资源后，才将该版本晋升为 `latest`。旧提交的发布不会覆盖较新的 main。固定部署与回退应使用 manifest 中的 `@sha256:...`，不要用可变标签锁定版本。

镜像验证中的代理探针使用合成上游，验证的是交付路径与隔离边界；目标环境的登录、业务权限和提供方仍需运行验收。步骤与证据位置见 [image-release.md](docs/runbooks/image-release.md)。

## 能力边界

主线保留已授权的窄面接口，未恢复全部旧 AI handler。部分管理页对应的旧模型/MCP/设置服务尚未迁移，可能返回 404；Skills 激活未启用。历史独立 AI 服务与冻结数据库链还承担兼容验证，不能按名称直接删除。生产部署、所有可选外部组件和跨版本滚动升级不在当前 CI 的完整承诺范围内。

## 开发与许可

隔离任务入口为 `scripts/agent/Start-IsolatedTask.ps1`，任务范围与验收由 Spec 明确，验证后发布 Draft PR；合并和部署由维护者授权。来源 SHA 固定在 `docs/upstreams.lock.json`，保留 `services/platform/LICENSE`、`services/ai/LICENSE`、`services/web/apps/workbench/license` 及相关 NOTICE。上游升级应单独提交评审并保留许可与来源记录。
