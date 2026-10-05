# P2/P3 运行手册（合成 dev 交付）

本手册覆盖 P2（可靠 RAG）与 P3（受控 Agent）核心能力的配置、默认边界、同源 dev 启动、回退与运行故障处理。所有配置通过环境变量或受控 env 文件注入；仓库不保存任何真实密钥。

## 1. 默认边界：一切能力默认关闭

- `p2.enabled=false`（缺省等同 false）：不装配 run/Worker/对象存储运行时；`POST /api/ai/v1/runs` 不可用；私有对象读写显式拒绝（`ClosedPrivateObjectStore`），不存在空实现静默成功。
- `p3.enabled=false`：Agent 运行时不装配。
- ES / Milvus / 自动记忆 / 模型摘要 / 外网搜索未开放：组件可以部署，但正式产品链路不会调用它们；不存在"装了就自动启用"。
- AI 服务在默认配置下可零配置启动（仅需数据库/Redis 连接），无需对象根等 P2 专属配置。

## 2. 专属开启（dev / 专属 profile）

显式打开所需能力（示例环境变量）：

```text
P2_ENABLED=true            # run/Worker/outbox/SSE/上传
P2_OBJECT_STORE_TYPE=fs    # 私有对象存储类型；其他值会响亮失败，不会静默回退
P2_OBJECT_STORE_ROOT=/data/private-objects   # 必须是非空专属目录，绝不指向公开目录
P3_ENABLED=true            # Agent 运行时（独立沙箱服务需另行提供并配置凭据）
```

约束：

- `p2.chat.egress.enabled` 与 `allowed-providers` 控制外发；未列入白名单的提供方零调用。开启真实提供方需要显式密钥与预算，属部署决定。
- 私有对象只落在 `P2_OBJECT_STORE_ROOT` 指定的专属目录；换成共享卷/S3/MinIO 是未实施的后续决定。
- 沙箱写工具（`sandbox_ticket`）只在显式配置的独立沙箱上执行；`p3.approval.initiator-enabled` 默认 false，真实业务审批主体政策未定稿前不要打开。

## 3. 数据库迁移

- 两侧迁移以仓库内 SQL 为唯一事实：platform V1–V6、AI V1–V12。
- 必需验证（CI 中自动执行）：`bash scripts/ci/verify-platform-migrations.sh`、`bash scripts/ci/verify-ai-migrations.sh`。后者在一次性数据库上验证冻结版本清单、结构锚点、角色隔离、漂移拒绝与错 schema 拒绝。
- 已发布的迁移文件不可修改；产品变更一律走下一增量版本。

## 4. 同源 dev 启动（单主机）

`infra/dev/compose.yaml` 提供统一启动：pgvector + Redis（digest 锁定）→ bootstrap/迁移一次性任务 → platform/ai 镜像 → web 镜像（静态前端 + 受限反向代理）。

```bash
cd infra/dev
cat > .env <<'EOF'
DEV_PG_PASSWORD=<随机>
DEV_REDIS_PASSWORD=<随机>
DEV_SERVICE_CREDENTIAL=<随机>
# 专属开启时：
# P2_ENABLED=true
EOF
docker compose up -d --build
```

- 浏览器只访问 web 暴露的单一端口（默认 8080）。`/api/`、`/auth/` 是仅有的两个被代理前缀；SSE 响应不缓冲；上传上限 50 MiB；其余路径只服务静态资源。
- 交付镜像：`ghcr.io/wonderchn/ruoyi-ai-agentscope-{platform,ai,web}`，三者共同出现在 release manifest，绑定同一 source commit 与镜像 digest。
- 所有真实值（数据库口令、服务凭证、外部化 `PROJECT_SERVICES_*` 占位符）通过 env 文件注入；env 文件不进仓库。

## 5. 回退

- 应用层：回退 = 用上一版本镜像重启；数据卷（PG 数据、私有对象、Redis）默认保留。
- 迁移层：只声明已验证的升级路径（全新安装、重复迁移幂等、漂移拒绝）。**未演练数据库降级，不承诺无损回退**；需要回退时使用备份恢复，不要手工改 schema。
- Agent checkpoint 携带执行版本（`p3-core-v1`）；不匹配的 checkpoint 会被拒绝而不是静默迁移，跨版本复用旧 checkpoint 是显式操作。

## 6. 运行故障处理

- 提交返回 `AUTHORIZATION_UNAVAILABLE`：平台在线授权检查在那一刻不可用（fail-closed，不会继续输出）。等待恢复后用新 run 重试；服务端不缓存旧授权、不自动重调模型。
- 提交返回 `SOURCE_CHANGED`：引用的来源在输出前发生了真实变化（删除/新版本/撤权）。确认来源状态后重试。
- `NEEDS_RECONCILIATION`（`MODEL_USAGE_UNKNOWN`）：模型调用已开始但用量/检查点未确认；不要手工把账清零，走对账流程。
- SSE 显示"事件流在终态前中断"：连接层中断且未收到终态帧；页面会用 afterSeq 游标续传，不会伪造完整输出。若页面显示"服务端终态失败：<errorCode>"，以该服务端事实为准。
- Worker/租约：节点崩溃后另一节点在租约到期（默认 6s）后接管（attempt/fence 递增）；旧节点的迟到持久写入会被 fence 拒绝。
- 迁移失败：PG 会整体回滚该迁移且不记录失败行；先修 SQL 再重放，不要 repair 历史表。

## 7. 验证入口

| 检查 | 命令 |
| --- | --- |
| 平台迁移 | `bash scripts/ci/verify-platform-migrations.sh` |
| 组合迁移（platform 6 + AI 12） | `bash scripts/ci/verify-ai-migrations.sh` |
| 原生故障矩阵（受理/接管/SSE） | `bash scripts/ci/verify-native-runtime.sh`（需要 docker + 两侧 Boot JAR） |
| Java 全量回归 | `mvn -B -ntp -f services/ai/pom.xml -Pci clean verify`；platform 用 `-Pdev` |
| 前端协议测试 | `node --import ./tests/ts-loader.mjs --test ./tests/*.test.ts`（在 `services/web/packages/events`；或在工作区根 `pnpm test`） |
