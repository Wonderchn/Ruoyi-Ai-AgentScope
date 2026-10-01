# tools/p1-boundary —— P1.2a 边界验收 runner

本目录只有两个文件：`run.ps1`（验收 runner）与 `README.md`（本文件）。runner 不修改仓库、
不接触 CI/工作流、不连接任何已有业务库；除 `-EvidenceDir` 下的证据与 `-WorkRoot` 下的临时目录外，
不在磁盘上留下任何东西。

关联文档：`mydocs/p1/01-p1-first-unit-spec.md`（§5 允许文件、§6 命令与参数、§7 B01–B14、§8 证据）、
`mydocs/p1/00-p1-plan.md`（§9 命令/环境/证据）。上一阶段同类 runner：`tools/p04-contract/run.ps1`
（本 runner 的命名、证据写入、失败计数、清理与安全约定沿用其house style）。

---

## 1. 目的，以及它**不能**证明什么

**能证明（closure boundary / 关闭边界）**：普通 AI 产品与普通 platform admin 在**真实进程**里，
尚未批准的旧身份与旧业务入口在任何 handler、Mapper、UserContext、模型/对象/MQ 调用**之前**被关闭；
后台 listener / 事务回查 / 定时扫描 / 启动期自动 I/O 直接触发也在 IO 之前受控关闭；
platform 普通构建不装配旧 AI 与 Harness、业务审批保留。

**不能证明（本 runner 明确不覆盖）**：

* **租户/资源隔离未完成**：本单元只关旧路径。跨 tenant 的 SQL/对象/缓存/记忆隔离、ACL/owner、
  policyVersion/aclVersion 撤权、canonical 鉴权属后续 P1 单元（P1.2b/P1.3a–d/P1.4）。
  Integration 的 B01–B13 断言里没有、也不允许出现"隔离已完成"的结论。
* 真实 run/SSE/Worker/恢复/审批执行链（P2/P3）不在范围；本 runner 不制造生产 run。
* 存量数据迁移正确性（需要 I1–I6 存量元数据与迁移 Spec）不在范围。
* CI 绿不能替代本 runner；本 runner 的结论也不能替代独立代码/证据评审（Spec §8）。

---

## 2. 快速开始

在仓库根执行（Windows PowerShell 5.1；JDK17；已配置本地 Maven）：

```powershell
# Unit：只跑 Spec 01 §6 的原生 mvn 命令，并逐类核对 Surefire XML
powershell -NoProfile -File tools/p1-boundary/run.ps1 -Mode Unit -EvidenceDir D:/AI-project/mydocs/p1/evidence/full/p12a-unit

# Integration：两真实 jar + runner 自有合成 PG17/pgvector、Redis、S3 mock
powershell -NoProfile -File tools/p1-boundary/run.ps1 -Mode Integration -EvidenceDir D:/AI-project/mydocs/p1/evidence/full/p12a
```

`-EvidenceDir` 必须是**绝对路径**，且必须位于仓库之外（例如 `D:/AI-project/mydocs/...`）；
相对路径或落在 `D:\AI-project\Ruoyi-Ai-AgentScope` 内的路径会被**用法拒绝**（`exit 2`，不写任何证据）。

---

## 3. 参数契约（名字固定，其他工具与 Spec 依赖）

| 参数 | 类型 | 必填 | 默认 | 语义 |
|---|---|---|---|---|
| `-Mode` | `Unit` / `Integration`（ValidateSet） | 是 | — | 选择验收模式，见 §4 / §5 |
| `-EvidenceDir` | string | 是 | — | 绝对路径；证据写到 `$EvidenceDir\<executionId>\`；相对路径或仓库内路径 → `exit 2` |
| `-RunTag` | string | 否 | `p1b` + `yyyyMMddHHmmss` | 本轮唯一标识；同时作为合成容器的 owner label（`p1.boundary.owner=<RunTag>`） |
| `-RepoRoot` | string | 否 | `D:\AI-project\Ruoyi-Ai-AgentScope` | 仓库根；所有 `-f services/.../pom.xml`、`git` 命令都在此目录解析 |
| `-WorkRoot` | string | 否 | `D:\AI-project\.scratch\p1-boundary\work` | 临时工作根；每轮实际使用 `$WorkRoot\<executionId>`，清理时删除；落在仓库内 → `exit 2` |
| `-PlatformPort` | int | 否 | 绑定期空闲端口扫描（18082–18160 内首个可绑定端口，0=扫描失败） | platform jar 端口；`ENV-ports` 会再验一次 |
| `-AiPort` | int | 否 | 绑定期空闲端口扫描（19090–19168） | AI jar 端口；非法开关启动用 `+10` 起的相邻端口 |
| `-ForceBuildRoots` | switch | 否 | 关 | **评审辅助，不属于验收契约**：预检判定环境缺失时仍执行两条 `clean verify`，使 `BUILD-*` 从 NOT_RUN 变为真实结果。它永远不会把 NOT_RUN 变成 PASS |

退出码：`0` 通过或（Integration）仅因环境缺失而 NOT_RUN；`1` 存在 FAIL；`2` 用法拒绝（相对/仓库内 `-EvidenceDir`、仓库内 `-WorkRoot`、无法创建目录）。

---

## 4. Unit 模式语义

固定执行 Spec 01 §6 的 5 条原生命令（每条独立进程、独立采集原生 `exitCode`），覆盖 6 个规格必跑类：

| 命令（`-f` / `-pl`） | 覆盖的类 |
|---|---|
| `mvn -o -B -ntp -f services/platform/pom.xml -Pdev -pl ruoyi-admin -am test '-Dtest=P1LegacyAssemblyBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'` | `P1LegacyAssemblyBoundaryTest` |
| `mvn -o -B -ntp -f services/ai/pom.xml -Pci -pl framework -am test '-Dtest=SaasCapabilityBoundaryTest,P04PlatformAuthorizationClientTest' ...` | `SaasCapabilityBoundaryTest`、`P04PlatformAuthorizationClientTest` |
| `mvn -o -B -ntp -f services/ai/pom.xml -Pci -pl system -am test '-Dtest=SaasEntryBoundaryTest,SaTokenConfigTest' ...` | `SaasEntryBoundaryTest`、`SaTokenConfigTest` |
| `mvn -o -B -ntp -f services/ai/pom.xml -Pci -pl rag -am test '-Dtest=P1TriggerBoundaryTest,P04AssemblyBoundaryTest' ...` | `P1TriggerBoundaryTest`、`P04AssemblyBoundaryTest` |
| `mvn -o -B -ntp -f services/ai/pom.xml -Pci -pl agent -am test '-Dtest=P1McpStartupBoundaryTest' ...` | `P1McpStartupBoundaryTest` |

**逐类判据**（读取 `services/<domain>/<module>/target/surefire-reports/TEST-<fqcn>.xml`）：

1. XML 必须存在，且 `<testsuite tests>` **> 0**；
2. `failures = 0`、`errors = 0`、`skips = 0`（有 skip 即 FAIL，安全必跑类不允许 skip）；
3. `<testsuite name>` 必须等于 Spec 01 §5 推导出的精确 FQCN（同名类跑到别的包 → FAIL，并在 detail 写明差异）；
4. **XML 必须由本轮这条命令重写**（`LastWriteTimeUtc >= 本次调用开始时刻`）。陈旧 XML（上一轮/别的 agent 留下的）
   一律 FAIL —— 这正是"reactor BUILD SUCCESS 不能证明指定类跑过"的落地；
5. 命令原生 `exitCode != 0` 单独记一条 FAIL（`UNIT-MVN-<id>`，detail 里带第一条 `[ERROR]`，便于定位编译/spotless/依赖问题）。

XML 缺失、tests=0、陈旧、有 skip → **FAIL，永不写成 skip**。任一 FAIL → 入口 `exit 1`。

`-Dtest=` 指定了不存在的类时，Surefire 因 `-Dsurefire.failIfNoSpecifiedTests=false` 不报错，
但 XML 必然缺失或陈旧，因此仍判 FAIL：这就是本模式的护栏。

---

## 5. Integration 模式语义

契约（Spec 01 §6 + 00 §9）：构建本轮两个真实产品 jar，针对 **runner 自有合成环境**
（PostgreSQL 17 + pgvector、Redis、S3 兼容 mock）跑 B01–B13；唯一 `RunTag`/owner label；
独立 `migrate` 与 `app` 数据库账号；随机测试口令与 key；**绝不**连接本机/远端已有业务库、缓存或对象存储，
**绝不**复用 P0.4 容器或远端 LabHost。

### 5.1 预检（任何环境都真实执行）

| 检查 | 内容 |
|---|---|
| `ENV-jdk17` / `ENV-maven` | `JAVA_HOME=D:\develop\java\jdk-17.0.18.8-hotspot` + `D:\develop\apache-maven-3.9.1\bin\mvn.cmd -version` |
| `ENV-evidence-path` / `ENV-workroot-safety` | 绝对路径 + 在仓库外 |
| `ENV-ports` | `-PlatformPort` / `-AiPort` 当前空闲 |
| `ENV-container-runtime` | 依次探测 `docker version` / `podman version` / `docker-compose version` / `nerdctl version`、`C:\Program Files\Docker`、`\\.\pipe\docker_engine`、WSL（`wsl.exe -l -v`，有发行版时再探 `wsl.exe -e sh -c "command -v docker \|\| command -v podman"`）。全部不可用 → 闸门关闭，原因代号 `NO_CONTAINER_RUNTIME` |
| `ENV-substitute-scan` | 只做 TCP 连接探测（不认证、不查询）：`127.0.0.1:5432/6379/9000/15434/3306/19530`。发现监听 → 每个写一条 `REFUSE-substitute-*`，说明**探测到但拒绝使用**及其原因 |
| `ENV-provider-key-isolation` | 列出父进程可见的真实 provider key 变量；子进程启动前这些变量一律被 runner 生成的随机值覆盖 |

### 5.2 环境闸门（fail closed）

* 闸门关闭时：`BUILD-*`、`ENV-compose-*`、`ENV-db-accounts`、`ENV-fixtures`、`PROBE-*`、`BOOT-*`、
  `B01`–`B13`、`DB-*`、`LISTENER-*`、`CHECKER-*`、`CASES-complete`、`CLEANUP-owned-containers`、
  `CLEANUP-synthetic-secrets` **共 31 项逐条写 NOT_RUN**，每条都带阻塞原因代号与说明；
  **不启动任何 jar、容器或数据库连接**，入口 `exit 0`。
* 收尾一致性 `INVENTORY-integrity` 强制两条规则：
  1. 计划清单（31 项）与实际产出必须一一对应，多一条少一条都 FAIL；
  2. 闸门关闭时**不允许任何计划项是 PASS**（防止把"没跑"伪装成"通过"）；闸门打开时不允许任何计划项不是 PASS。
* `-Mode Integration` 与 `tools/p04-contract/run.ps1 -Mode Integration` 的退出码口径**故意不同**：
  P04 把"缺靶场"判为失败（`exit 1`），本 runner 按 Spec 01 §6 把"环境缺失"判为 `NOT_RUN` 且 `exit 0`。
  两者都不允许把缺失写成 PASS。

### 5.3 happy path（闸门打开后才执行；本机从未执行）

1. `Initialize-SyntheticSecrets`：`pgSuperuser/platformMigrate/platformApp/aiMigrate/aiApp/redis/s3Access/s3Secret/platformJwt/aiServiceCredential/aiDelegationSigning/fixtureUser` 全部随机生成；父进程真实 provider key 覆盖为随机值。
2. `Test-P04ContainerIsolation`：`docker compose ps --format json` 证明本轮 compose 项目内没有 `p04-*` 容器。
3. 合成端口再次空闲扫描（PG 15432–、Redis 16379–、S3 19000–），**不复用 5432/6379/9000**。
4. `Write-ComposeFile`：compose 文件只含 `${P1B_*}` 占位符（口令经进程环境注入），落盘副本可安全归档到证据。
5. `ENV-compose-up`：`docker compose --project-name p1b-<runtag> up -d --wait`，随后按 `label=p1.boundary.owner=<RunTag>` 核对恰好 3 个容器。
6. `ENV-db-accounts`：创建 `platform`/`ai`/`extensions` schema 与 4 个角色；用 `migrate` 账号**逐字**执行仓库内迁移 SQL 原文（记录每个文件 sha256）。
7. `ENV-fixtures`：两个合成 tenant 各一条同名用户（仅合成记录）。
8. `PROBE-availability` / `PROBE-ai-facts`：Spec 01 §5 的 `P1ProductBoundaryProbe` + 真实 `RagentApplication` 进程写出的 facts 文件。
9. `BOOT-*`：真实 `ruoyi-admin.jar`（`-Pdev`，PG datasource）与真实 `bootstrap-*.jar`（默认配置 + 显式 `--p04.enabled=false`）启动；就绪 = 日志出现 ApplicationReady/Started + 端口可连 + 进程存活（**不用 404 冒充健康**）。
10. `B01`–`B13`：平台两租户真实登录控制组、未注册 health 404、旧登录/用户管理四类凭证统一 404 且同 code/errorCode、旧映射与未知路径 404、伪造 header/body 不进安全日志、尾斜线/encoded/FORWARD-ASYNC-ERROR/OPTIONS 规范化、`/internal/ai/v1/*`+`/api/ai/v1/*`+`/p04/*` 404、四个非法开关启动必须非零退出、listener/checker 注册数 0 且直接触发受控关闭、schedule 观察窗口内零扫描/claim/状态更新/锁/任务提交、四类 startup 能力零建桶/零 index/零 publicRead/零 MCP 连接、platform jar 内容与残留 `/workflow/run` 排除项。
11. `DB-snapshot-before` / `DB-snapshot-after` / `DB-no-business-delta`：对 `platform`/`ai`/`extensions`/`public` 全部基表做 **行数 + 行哈希**（`md5(string_agg(md5(row::text)))`）快照并逐表比对（`flyway%` 表排除，见 §8），能查出 update/delete 而不只是新增。
12. `CASES-complete`：B01–B13 全部真实执行且 PASS，缺一不可。

### 5.4 `P1ProductBoundaryProbe` facts 契约

B09–B13、listener/checker 注册数与 handler 调用计数来自 probe facts JSON（默认路径
`<evidence>/probe/ai-runtime-facts.json`）。**注意：这是 runner 要求的契约，仓库现有 probe 尚未按它输出**
（见 §10）。probe 必须写出：

```json
{
  "contextStarted": true,
  "routes": ["..."], "legacyMappings": [{ "path": "/..." }],
  "mqConsumers": [], "transactionCheckers": [], "checkerMapperInvocations": 0,
  "storageBeans": [], "createdBuckets": [], "createdIndexes": [], "publicReadGrants": 0,
  "mcpConnects": 0, "beanLifecycleOk": true,
  "directTrigger": { "listener": [{ "closed": true }], "checker": [{ "closed": true }], "userContextLeaks": 0 },
  "schedule": { "observedSeconds": 2, "dbScans": 0, "claims": 0, "statusUpdates": 0, "redisLocks": 0, "submittedTasks": 0 },
  "dispatch": { "forwardClosed": true, "asyncClosed": true, "errorRecursion": 0 },
  "counters": { "handlerExecutions": 0, "userMapperInvocations": 0, "authServiceInvocations": 0,
                "saTokenLogins": 0, "mapperQueries": 0, "mqSends": 0, "objectWrites": 0, "modelCalls": 0 }
}
```

---

## 6. 当前 NOT_RUN 与原因（本机实测，2026-10-01）

**Integration 全部启用项 NOT_RUN，原因代号 `NO_CONTAINER_RUNTIME`。** 具体是计划清单 31 项
（`gated-inventory.json` 的 `plannedGatedChecks`）加上预检自身的 `ENV-container-runtime`，
`results.json` 里共 **32 条 NOT_RUN**；同轮 `PASS=9`（8 条环境无关检查 + `INVENTORY-integrity`），`FAIL=0`。
实测探测结果（同一份写入 `preflight.json` 与 `manifest.environmentGate.probes`）：

| 探测 | 结果 |
|---|---|
| `docker version` / `podman version` / `docker-compose version` / `nerdctl version` | 可执行文件均不在 PATH（`executable not found`） |
| `Test-Path 'C:\Program Files\Docker'` | False |
| `Test-Path '\\.\pipe\docker_engine'` | False |
| `wsl.exe -l -v` | exit 1：`未安装适用于 Linux 的 Windows 子系统…` → 无发行版，故未继续探 WSL 内引擎 |
| `ENV-substitute-scan`（5432/6379/9000/15434/3306/19530） | 全部无监听 → 本轮**没有**需要拒绝的替身资源（refusals 为空） |

因此本机**无法**建立合成 PG17/pgvector、Redis、S3 mock，也**无法**启动两个真实 jar。这不是 PASS、
也不是假 FAIL，而是 `NOT_RUN`，入口 `exit 0`。**Unit 模式不需要容器，可以在本机真实执行**（见 §7）。

---

## 7. 如何读证据

证据根：`<EvidenceDir>\<executionId>\`（`executionId` = 运行开始的 UTC `yyyyMMddTHHmmssZ`）。

两种模式共有：

| 文件 | 内容 |
|---|---|
| `manifest.json` | `headSha`/`branch`/`dirtyPathCount`+`dirtyPaths`、`startedUtc`+`startedAsiaShanghai`（UTC 与 Asia/Shanghai 双时间戳）、`host`（PS/Java/Maven 版本）、`ports`、`requiredClasses`、`unitCommands`、`specFileSha256`、`nativeCommandCount`、`ownedProcessIds`、`ownedContainerNames`、`cleanup`、`secretKeyNames`（只有 key 名，没有值）、`resultCounts`、`blockingResults`、`refusedResults`、`harnessEntryExitCode`；Integration 另有 `environmentGate`（含全部探测记录）、`refusals`、`notRunCheckIds`、`integrationInventoryPlan`、`closingStatement` |
| `native-commands.json` | 每条原生命令：`executable`、`arguments`（已脱敏）、`commandLine`、`startedUtc`/`finishedUtc`/`durationMs`、原生 `exitCode`、`logFile` |
| `results.json` | 逐条检查：`id` / `target` / `status` / `detail`。状态集 **PASS / FAIL / NOT_RUN / REFUSED**；`REFUSED` 只用于"检测到但拒绝使用"，不算 PASS、不算 FAIL、不阻塞 |
| `cleanup.json` | 清理明细：`owned JVM`（PID+结果）、`自有容器`、`env 变量清除`、`临时目录删除`（含被拒绝的删除） |

Unit 模式另有：

| 文件 | 内容 |
|---|---|
| `surefire-summary.json` | 逐类：`expectedFqcn`、`suiteName`、`tests/failures/errors/skips`、`caseCount`、`xmlPath`、`xmlSha256`、`refreshedByThisRun`、`sourcePath`/`sourceSha256`、`archivedXml`、`status`、`detail` |
| `unit-<invocation>.log` | 每条 mvn 的完整输出（已脱敏）。注意：javac/Maven 的本地化（中文）提示可能因控制台代码页显示为乱码；判定不看这些文字，只看原生 exitCode 与 UTF-8 解析的 Surefire XML |
| `surefire/<domain>/<module>/TEST-<fqcn>.xml` | 本轮实际解析的 XML 原件副本（与 `xmlSha256` 对应） |

Integration 模式另有：`preflight.json`（全部探测）、`refusals.json`、`gated-inventory.json`（闸门决定、
计划清单、NOT_RUN id、`-ForceBuildRoots` 记录）、`http.jsonl`（脱敏 HTTP 记录）、`db-before.json`/
`db-after.json`/`db-delta.json`、`listener-registration.json`、`compose/docker-compose.yml`（只有占位符）、
`jvm-logs/**`（脱敏后的 jar 输出）、`build-*-clean-verify.log`。

本次已实测（同一批命令、原生 exitCode 逐个采集）：

* **Integration**：`PASS=9 FAIL=0 NOT_RUN=32 REFUSED=0`，`INVENTORY-integrity` PASS，入口 **exit 0**
  （证据目录 `D:\AI-project\mydocs\p1\evidence\full\p12a\<executionId>\`）。
* **Unit**：`PASS=18 FAIL=0 NOT_RUN=0 REFUSED=0`，入口 **exit 0**；8 个类全部 `refreshed=True`、
  `skips=0`：`P1LegacyAssemblyBoundaryTest` 7、`SaasCapabilityBoundaryTest` 9、
  `P04PlatformAuthorizationClientTest` 22、`SaasEntryBoundaryTest` 13、`SaTokenConfigTest` 3、
  `P1TriggerBoundaryTest` 10、`P04AssemblyBoundaryTest` 4、`P1McpStartupBoundaryTest` 5
  （证据目录 `D:\AI-project\mydocs\p1\evidence\full\p12a-unit\<executionId>\`）。
* 同一 runner 在 working tree 编译失败 / spotless 未格式化期间的真实表现（也曾实测）：
  4 条 AI 命令 `exit=1`、`buildSuccess=False`，对应类因 XML 未刷新被判 **FAIL**（不是 skip）——
  说明"reactor 绿"与"指定类真的跑过"被严格分开。

---

## 8. 清理行为

1. **只停止本脚本自己启动的 JVM**：写 `$WorkRoot\<executionId>\<side>-<state>.pid`；停止前核对
   (a) PID 在 `$script:StartedProcesses`（本进程启动记录）中，(b) `Get-CimInstance Win32_Process` 的
   `CommandLine` 匹配 `java`。两者任一不满足 → 拒绝停止并写 FAIL 证据。**绝不 `Stop-Process -Name java`**。
2. **只销毁本轮自有容器**：`docker compose --project-name p1b-<runtag> down --volumes --remove-orphans`，
   随后按 `label=p1.boundary.owner=<RunTag>` 复查残留必须为 0。P0.4 容器/远端 LabHost 从不引用。
3. **删除前校验归属**：`Remove-OwnedPath` 解析绝对路径，必须严格位于 `$WorkRoot` 或 `$EvidenceDir` 之下，
   否则拒绝删除并写 `REFUSED`（`CLEANUP-refused-path`）。
4. **口令/密钥清理**：本轮随机口令只经子进程环境传递；结束时从进程环境清除
   `PGPASSWORD`/`P1B_*`/`AI_DB_PASSWORD`/`PLATFORM_DB_PASSWORD`/`REDIS_PASSWORD`，证据里只保留 key 名。
5. 每轮结束删除 `$WorkRoot\<executionId>`（自有临时目录）；**证据目录保留**。

---

## 9. 安全与不变量

* 不修改仓库、`main`、CI 工作流、`.agent`、`scripts/agent`、`AGENTS.md`、P04 实现或工具脚本；本 runner 只读这些。
* 证据与临时目录必须在仓库外；否则 `exit 2`。
* 不连接、不认证、不查询任何已有业务库/缓存/对象存储；探测仅 TCP connect。
  发现替身资源 → `REFUSED` 留证，绝不当成合成环境使用。
* 不复用 P0.4 容器、不访问远端 LabHost、不读取本机业务库配置；合成端口全部另行扫描。
* 子进程只拿到 runner 随机生成的 provider key / 数据库口令；父进程真实 key 一律覆盖。
* 任何写盘前的文本都过 `Protect-LogText`，命令行参数过 `Protect-NativeArgument`（`password=`/JWT/本轮随机值 → `[REDACTED_*]`）。

---

## 10. 已知未实现 / 未验证（诚实清单）

| 项 | 状态与原因 |
|---|---|
| Integration happy path 的**任何一步** | 本机无容器运行时，从未执行（§6）。代码已实现且全部门控在预检之后，但**未验证**：compose 启动、角色/迁移、两 jar 启动、B01–B13、快照比对 |
| `P1ProductBoundaryProbe` | 查找规则：先找 Spec 01 §5 的精确路径 `services/ai/rag/src/test/java/com/nageoffer/ai/ragent/boundary/P1ProductBoundaryProbe.java`；不在则在整个 `services/` 下按文件名唯一匹配，并在 detail 里写明 `specPathMatch=false`（位置偏差只记录、不放过）。当前仓库里它位于 `services/ai/system/src/test/java/com/nageoffer/ai/ragent/user/config/P1ProductBoundaryProbe.java`（`specPathMatch=false`） |
| probe facts 契约 | §5.4 是 **runner 要求的**契约，不是仓库现状。runner 逐字段校验：缺字段 → `PROBE-ai-facts` FAIL 并**点名缺哪些字段**，B09–B12 与 listener/checker 注册数记 NOT_RUN → `CASES-complete` FAIL → `exit 1`（fail closed，不猜默认值） |
| 现有 probe 的实际输出 | 仓库现有的 `P1ProductBoundaryProbe`（位于 system 模块）是**独立 JVM 探针**：`main()` 打印一行带前缀的 JSON，字段为 `probe`、`dispatcherTypesAllowedThroughGate`、`dispatcherTypesRejectedByGate`、`dispatcherTypesReachingChainOnBusinessPath`、`businessPathRequestDispatchStatus`、`saasBoundaryConfigurationOnClasspath`、`entryFilterOnClasspath`、`filterRegistrationBeansVisible`、`applicationContextVisible`、`filterRegistrationScan`、`properties`、`probeError`。它**没有** bean/mapping 清单、listener/checker 注册数、直接触发计数、schedule/bucket/index/MCP 计数，因此 B09–B13 目前**没有事实来源**。要在有容器运行时的机器上真正跑完，需按 §5.4 补齐产品上下文事实来源（或把 runner 的契约改成与 probe 一致的字段名——两者取其一，不能默默放宽） |
| 合成环境镜像 tag | `pgvector/pgvector:0.8.6-pg17`、`redis:7.4-alpine`、`minio/minio:RELEASE.2025-04-22T22-12-26Z` 未经本机验证存在；首次真实运行时若镜像不可得，`ENV-compose-up` 会 FAIL（不会改用别的库） |
| platform jar 跑在合成 PG 上 | 依赖 `-Pdev` 的 PostgreSQL datasource（`PLATFORM_DB_*`）与 platform 迁移脚本；"platform 在 PG 上完整启动"本身未验证 |
| AI jar 的合成配置覆盖 | 依据 `services/ai/bootstrap/src/main/resources/application.yaml` 的 `AI_DB_URL`/`AI_DB_USER`/`AI_DB_PASSWORD`、`spring.data.redis.*`、`rag.storage.s3.endpoint` 注入；属性名与占位符变量名（含若干被扫描器替换的长变量名）首次真实运行需核对，必要时调整 `Start-ProductJar` |
| 两租户登录 fixture | `platform.sys_user` 的列与口令哈希口径为假设；B01 只断言 HTTP 200 + `code=200`，fixture 需按真实 schema 调整 |
| `DB-*` 快照范围 | 排除 `flyway%` 表（迁移记账表），其余基表全量行数 + 行哈希 |
| B08 非法开关 | 假定产品 fail-fast；每个非法启动最多等 180 s |
| B06 dispatcher 断言 | 依赖 probe facts 的 `dispatch.*`；无 probe 时该检查 NOT_RUN |
| 真实两 jar 的完整 B01–B13 | 本单元从未在真实环境跑过；Spec 01 §6 明确这些命令"当前 NOT_RUN" |

评审演练（不需要容器）：`-ForceBuildRoots` 可只把两条 `clean verify` 真实跑掉，
其余仍按 §5.2 写 NOT_RUN —— 便于分别评审"构建根"和"集成验收"两半。
