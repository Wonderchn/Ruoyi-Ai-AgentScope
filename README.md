# Ruoyi-Ai-AgentScope

多租户 AI 平台：若依负责身份、租户、角色和业务管理，AgentScope 负责受控 Agent 执行，RAG 提供知识摄取与检索。当前主线采用 **platform 内嵌 AI 运行时 + 统一 PostgreSQL 迁移链 + 双前端工作区**。

已实现的核心包括身份与租户边界、知识摄取/检索、知识图谱、run 状态与幂等、Worker/outbox/SSE 重放、受控 Agent（含多 Agent 应用市场）、Skills 与 MCP 工具目录、媒体任务（图片/语音/视频）以及短剧 LLM-only API 面，前端为工作台与管理端双工作区。集成 CI 覆盖构建、权限、数据库迁移与合成运行期回归。部署仍需配置数据库、Redis、模型提供方、租户套餐和角色权限，并在目标环境验收；这不等于所有上游功能都已迁移。

管理端新增租户 AI 工作区与平台租户总览：按租户展示模型、目录副本、权限与**真实运行配额**；新租户创建时自动下发默认助手；双端前端已按 Apple HIG 重做设计令牌、壳层与核心页面。

## 租户 AI 工作区

管理端 `/admin/ai` 是租户自服务入口，`/admin/ai/tenants` 是平台视角。两边的数字都来自现有端点，不新增第二套权限：

| 页面 | 读者 | 内容 |
| --- | --- | --- |
| `/admin/ai` | 租户管理员 | 当前模型与版本、运行配额、24h 调用/token、能力入口（知识库/Agent/Skills/MCP）、AI 权限域、快捷动作（含导入默认 Agent） |
| `/admin/ai/permissions` | 租户管理员 | `ai:*` 权限按域目录、角色与套餐入口；说明 AI 权限就是菜单权限 |
| `/admin/ai/usage` | 租户管理员 | 24h/7d 会话/消息/活跃/成功率、运行配额（已结算/在途/剩余）、按模型/按天调用与 token、最近运行 |
| `/admin/ai/tenants` | 平台管理员 | 各租户 ACL、已发布运行配置、默认助手、Agent/Skills/MCP/知识库数量、7 天调用/token |

运行配额来自准入层真实扣减的 `ai_tenant_budget` 与 `ai_budget_reservation`：`SETTLED` 计入消耗、`RESERVED` 计入在途、`RELEASED` 不消耗；没有预算行的租户回落部署默认值。它是**合成单位**，回答“本租户还能不能继续跑”，不是人民币计费金额；部署级花费信封是另一套口径，页面分开标注，不互相换算。

新租户创建时在同一事务内初始化 ACL、默认运行配置，并从平台模板 `__public_template__` 复制“默认助手”和 Prompt 槽位；租户也可以在 AI 工作区手动执行“导入默认 Agent”（幂等，不覆盖已有改动）。租户目录自服务由 `ai.integration.tenant-catalog-self-service` 控制，默认关闭。

## 架构与目录

| 路径 | 用途 |
| --- | --- |
| `services/platform` | Java 17 / Spring Boot 3 平台；`ruoyi-ai-*` 模块承载当前内嵌 AI 能力 |
| `services/web/apps/workbench` | Vue 3 用户工作台，镜像入口 `/` |
| `services/web/apps/admin` | Vue 3 管理端，镜像入口 `/admin/` |
| `services/web/packages/events` | run/SSE/RAG 协议与传输 |
| `services/web/packages/platform-client` | 共享身份、请求、权限与错误处理 |
| `services/platform/docs/script/sql/legacy-ai` | 冻结的旧 AI V1..V12 迁移档案，仅作旧库升级验证输入 |
| `infra/docker` | 发布链的两个镜像构建定义（platform、web） |
| `scripts/ci` | 数据库、运行期、发布清单和镜像交付验证 |

未装配的旧 `ruoyi-chat`、`ruoyi-aiflow`、`ruoyi-generator` 模块已退出源码与 Maven reactor。原独立 AI 服务（`services/ai`）已退出仓库，其能力由 platform 的 `ruoyi-ai-*` 模块承载；冻结的 V1..V12 迁移档案留在 `services/platform/docs/script/sql/legacy-ai`，只用于旧库升级验证，不参与新装。仍在使用的 `ruoyi-ai-*`、`com.nageoffer.ai.ragent.*`、业务 `ruoyi-workflow` 和原有迁移历史继续保留。原上游来源与许可证见 [upstreams.lock.json](docs/upstreams.lock.json)。

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
```

需要外部组件或模型服务的测试范围见 [external-tests.md](docs/runbooks/external-tests.md)。PR 必须通过现有 `ci-required` 门禁；涉及镜像交付的 PR 还要通过实际容器内的双前端、资源文件与代理边界检查。

## 运行与数据库

内嵌运行需明确选择 `dev,embedded` 或 `prod,embedded` profile，例如：

```bash
SPRING_PROFILES_ACTIVE=prod,embedded java -jar services/platform/ruoyi-admin/target/ruoyi-admin.jar
```

这是 profile 选择示例，数据库/Redis、提供方和身份配置需先按运行环境注入。`embedded` profile 显式装配 P2/P3；未选择该 profile 的基础配置不自动启用这些能力。Skills 激活保持关闭，外部沙箱、真实模型外发和审批需要各自的配置与授权。功能权限与租户套餐的关系见下文「权限与租户隔离」。

统一迁移链以 `services/platform/docs/script/sql/postgres` 和独立的 [unified-schema-contract.json](scripts/ci/unified-schema-contract.json) 为准。版本并不连续；不要自行填补编号或重新执行另一条历史链。CI 同时验证统一库新装、从 platform V6 + AI V12 升级、重复迁移和漂移拒绝，继续保留旧链检查。既有迁移不可改写。

配置入口见 [configuration.md](docs/configuration.md)；运行与回退见 [p2-p3-dev.md](docs/runbooks/p2-p3-dev.md)。真实凭据只通过环境注入。应用示例配置见 `services/web/apps/workbench/.env.example`，前端 `VITE_CLIENT_ID` 是公开客户端标识，必须与平台 `sys_client` 配置一致。

## 权限与租户隔离

租户级功能权限只有一个来源：`sys_menu.perms` → `sys_role_menu` → `sys_tenant_package.menu_ids`。AI 能力的 `ai:*` 权限与系统菜单权限使用同一套角色、菜单和套餐，没有独立的 AI 权限表；角色未授予或套餐未包含的菜单不会生效。租户管理员默认只拿到本租户套餐内的权限。

AI 资源另有一层数据面授权：资源归属与共享 ACL、策略版本、执行许可和撤权屏障。它判断“这个主体能否读写这条知识库、会话或运行”，不替代上面的功能权限；缺少租户或主体时一律拒绝，检索授权集合为空时不会扩大为全库查询。

平台模板与平台目录仍只有平台管理身份可写：内置超级管理员、`superadmin` 角色或部署方创建的 `ai_catalog_admin` 角色。内嵌部署开启 `ai.integration.tenant-catalog-self-service=true` 后，租户管理员凭对应 `ai:*` 权限管理**本租户自己的** Agent/Skills/MCP 副本，行级 `tenant_id` 过滤保持不变；未开启时（默认）仍要求平台目录管理身份。平台模板租户 `__public_template__` 对租户只读。租户级 AI 能力（知识库、会话、运行、检索、AIFlow、短剧等）只按角色与套餐权限放行。

启用 `/mcp` 时，端点要求登录令牌或内部服务凭证；登录令牌的工具调用按工具能力判定，未登记的工具拒绝。凭据与工具授权配置只通过环境注入。

## GitHub 容器镜像

| 镜像 | 用途 |
| --- | --- |
| [ruoyi-ai-agentscope-platform](https://github.com/Wonderchn/Ruoyi-Ai-AgentScope/pkgs/container/ruoyi-ai-agentscope-platform) | 当前平台；内嵌部署设置 `SPRING_PROFILES_ACTIVE=prod,embedded` |
| [ruoyi-ai-agentscope-web](https://github.com/Wonderchn/Ruoyi-Ai-AgentScope/pkgs/container/ruoyi-ai-agentscope-web) | 工作台 `/` 与管理端 `/admin/`，同源代理到 `platform:6039` |

独立 AI 兼容服务已不在仓库，也没有对应镜像。`infra/dev/compose.yaml` 起的就是内嵌形态（platform `dev,embedded` + web，单后端）；旧独立 AI 服务只剩冻结的 V1..V12 迁移档案，供旧库升级路径验证使用。

main 验证通过后发布 `ghcr.io/wonderchn/<镜像名>:sha-<完整提交SHA>`。release manifest 将两个不可变 digest、源码 SHA 和 [兼容性声明](docs/release-compatibility.json) 绑定；干净 runner 匿名拉取并验证来源标签、内嵌装配和双前端资源后，才将该版本晋升为 `latest`。旧提交的发布不会覆盖较新的 main。固定部署与回退应使用 manifest 中的 `@sha256:...`，不要用可变标签锁定版本。

镜像验证中的代理探针使用合成上游，验证的是交付路径与隔离边界；目标环境的登录、业务权限和提供方仍需运行验收。步骤与证据位置见 [image-release.md](docs/runbooks/image-release.md)。

## 能力边界

主线保留已授权的窄面接口，未恢复全部旧 AI handler。知识库、摄取流水线、模型与提供方、知识图谱等租户级 AI 管理页已接到网关活族路由，不再依赖旧服务；Agent 定义、Skills、MCP 工具目录在默认装配下属于平台目录面，除对应 `ai:*` 权限外还要求平台目录管理身份；开启租户目录自服务后，租户管理员只能管理本租户副本，平台模板仍只读。Skills 激活未启用。独立 AI 服务已退出仓库，但冻结的旧 AI 迁移链仍保留在 `legacy-ai` 目录并继续承担旧库升级验证，不能按目录名判断可以删除。生产部署、所有可选外部组件和跨版本滚动升级不在当前 CI 的完整承诺范围内。

## 开发与许可

隔离任务入口为 `scripts/agent/Start-IsolatedTask.ps1`，任务范围与验收由 Spec 明确，验证后发布 Draft PR；合并和部署由维护者授权。来源 SHA 固定在 `docs/upstreams.lock.json`，保留 `services/platform/LICENSE`、`services/platform/LICENSE-ragent`、`services/web/apps/workbench/license` 及相关 NOTICE。上游升级应单独提交评审并保留许可与来源记录。
