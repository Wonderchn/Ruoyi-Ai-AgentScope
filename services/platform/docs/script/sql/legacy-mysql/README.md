# 旧 MySQL AI 域行数据迁移（E2 §5.1b）

平台侧旧 AI 域（`chat_*` / `knowledge_*` / `agent_info` / `mcp_*` / `t_workflow*` /
`short_drama_*` / `trace_*`）**只存在于 MySQL**。V9 补了这些表的 PostgreSQL 结构、V10 补了
没有任何脚本的 `chat_config`，但没有任何迁移搬它们的行。本目录提供落地区（staging）契约，
`postgres/V11__legacy_ai_domain_data.sql` 提供行数据迁移。

合并表（V7 按 AI 侧形状建表、V9 只追加平台侧独有列）里有平台侧 NOT NULL 列**没有同名的
MySQL 列**，例如 `ai_conversation.member_id`、`ai_message.conversation_id`。这类取值由本目录的
`20-legacy-ai-domain-adaptation.sql`（落地区适配，**非 Flyway 迁移**）按显式来源计算并写回
落地区，V11 再把它当普通列复制——迁移脚本因此不需要臆造取值，V11 驱动器也保持与表无关。

## 为什么需要落地区

MySQL 行数据必须先以**原始表名、原始列名**落到统一库的一个临时 schema（默认
`legacy_mysql`），V11 才能复制。这样做是为了让类型转换、租户规范化与枚举映射都发生在
**可见的迁移里**，而不是隐藏在导入工具的参数中：

- V9 把 `tenant_id` 从 MySQL `bigint` 规范化为 `varchar(64)`（E2-D1：数值旧值按**文本**保留，
  `0` → `'0'`，**不并入**平台默认租户 `'000000'`）。落地区保留 MySQL 原类型，所以 V11 必须
  显式写 `::text`；
- `ai_knowledge_document.status` 是**编码差异**（MySQL `smallint` 0/1/2/3 vs 统一
  `VARCHAR(16)` `'pending'/'running'/'success'/'failed'`）。落地区保留 `smallint`，V11 通过
  `platform.ai_legacy_domain_enum_map` 做值映射，映射不存在即**拒绝复制**；
- 派生列（`member_id`、`ai_message.conversation_id`）由落地区适配脚本按登记来源计算；
  来源缺失、断关联、跨租户/跨成员、父键歧义、空必填值一律**报错拒绝**（带行数与样本），
  不造默认值。派生列在落地区按来源宽度声明（如 `conversation_id varchar(32)`），因此
  V11 仍会对超出统一列宽度的值做超长预检并拒绝，不截断。

## 操作步骤

```bash
# 1) 建落地区（本目录的生成物，不是 Flyway 迁移）
psql -v ON_ERROR_STOP=1 -f services/platform/docs/script/sql/legacy-mysql/10-legacy-ai-domain-staging.sql

# 2) 把 MySQL 行按原始列名导入 legacy_mysql
#    方式 A：pgloader 整库落地
pgloader mysql://user@host/ruoyi_ai 'postgresql:///unified?schema=legacy_mysql'
#    方式 B：逐表 TSV + COPY（本机无 pgloader 时）
mysql -h host -u user -p ruoyi_ai -e "SELECT * FROM chat_model" --batch --raw > chat_model.tsv
psql -c "\copy legacy_mysql.chat_model FROM 'chat_model.tsv'"

# 3) 落地区适配与校验（由落地区 owner/DBA 执行；同一事务，拒绝即整体回滚）
psql -v ON_ERROR_STOP=1 -f services/platform/docs/script/sql/legacy-mysql/20-legacy-ai-domain-adaptation.sql

# 4) 跑统一迁移链（Flyway 应用 V11，行数据在此时搬入 platform）
#    见 scripts/ci/verify-unified-migrations.sh 的同一序列

# 5) 对账
psql -c "SELECT legacy_table, mode, rows_read, rows_inserted, rows_skipped_existing, blocked_reason
           FROM platform.ai_legacy_domain_migration_audit ORDER BY mode, legacy_table"

# 6) 验证通过后删除落地区（临时对象，不留在生产库）
psql -c "DROP SCHEMA legacy_mysql CASCADE"
```

没有 `legacy_mysql` schema 时 V11 是**空操作**：每张表在审计表里记一行 `mode='absent'`。
全新安装、以及从未跑过 MySQL 平台侧 AI 域的部署都属于这种情况（此时也不执行第 3 步）。

**权限边界**：落地区与适配脚本由落地区 owner（DBA）创建、装载与执行；迁移身份
（Flyway 运行账号）对 `legacy_mysql` 只有 `USAGE` + `SELECT`，V11 全程只读落地区。
派生列在适配脚本里收紧为 `NOT NULL`，所以 V11 拿到的落地区要么完整、要么直接报错。

## V11 的语义

| 机制 | 说明 |
| --- | --- |
| 显式列映射 | `platform.ai_legacy_domain_migration_map` 逐列登记（`identity` / `cast_text` / `enum` / `coalesce_text` / `constant`）。**绝不按位置映射**；落地区缺列或统一表缺列都直接报错拒绝 |
| 逐表模式 | `platform.ai_legacy_domain_migration_table_map.mode`：`copy` 或 `blocked`。**统一表存在 NOT NULL 且无默认值、旧表又无同名列**时一律 `blocked`，原因写入审计表 |
| 复制顺序 | `...table_map.copy_order`（生成器按冻结外键图计算，父表在前）：V7 的复合外键立即生效，子表先复制会因外键失败 |
| 已证明的列别名 | `agent_info.agent_name → ai_agent_profile.name`（Agent.java 的 agentName）；`chat_session.session_title → ai_conversation.title`（ChatSession.java 的 sessionTitle 与统一读路径的 title）。原列都保留，200→64 / 255→128 的名称先预检超长，NULL/空串/冲突拒绝，无默认名称。当前 24 张 copy、3 张 blocked |
| 派生列（落地区适配） | `chat_session.member_id` / `chat_message.member_id` = canonical `platform:<tenantId>:<userId>`；`chat_message.conversation_id` = 同租户同成员的 `chat_session`（`session_id → id`）的公开 `conversation_id`。规则与来源登记在注册表 `note`，取值由 `20-legacy-ai-domain-adaptation.sql` 计算 |
| 幂等 | 按统一表主键做 `IS NOT DISTINCT FROM` 反连接 + `ON CONFLICT DO NOTHING`；已存在的行不改写并计入 `rows_skipped_existing` |
| 丢弃检测 | 复制行数必须等于「读到 − 按键已存在」；被其它唯一约束吞掉的行或落地区重复键都会让迁移失败，而不是静默丢行 |
| 宽度预检 | 旧列宽于统一列时先做超长预检，超长即报错并给出条数与样本值，**不截断** |
| 枚举映射 | `platform.ai_legacy_domain_enum_map`；落地区出现映射外的取值（含 NULL）即拒绝复制。该校验对 `blocked` 表同样执行，映射因此**现在就对着真实数据验证过** |
| 行数对账 | 每表一行审计（`rows_read = rows_inserted + rows_skipped_existing`、`unified_rows = unified_rows_before + rows_inserted`），不满足即迁移失败 |
| 身份序列 | 显式写入 identity 列后同步序列，避免后续应用插入与已迁移行主键冲突 |

### 为什么还有 `blocked` 表

`knowledge_info` / `knowledge_attach` / `knowledge_fragment` 三个目标是**合并表**：
V7 按 AI 侧形状建表，V9 只追加平台侧独有列（E2 §2.4b）。它们的统一表里有 AI 侧 NOT NULL 列，
旧 MySQL 行无法提供同名取值：

| 目标表 | 需要平台侧决定的列（无同名列） |
| --- | --- |
| `ai_knowledge_base` | `collection_name`、`created_by`、`owner_member_id` |
| `ai_knowledge_document` | `kb_id`、`doc_name`、`file_type`、`file_url`、`created_by` |
| `ai_knowledge_chunk` | `kb_id`、`chunk_index`、`created_by` |

其中 `collection_name` 的取值规则有源码证据但**依赖部署配置**
（`MilvusVectorStoreStrategy`/`QdrantVectorStoreStrategy` 用
`vector-store.*.collectionname + kid`，前缀来自配置），`file_url` 需要平台对象存储登记
（旧 `knowledge_attach.oss_id` → `sys_oss.url`）。这类取值属于平台 Mapper 适配
（E2 §5.1c / E5），迁移脚本臆造等于伪造数据，因此**如实记为 `blocked`** 而不是猜。

`chat_session` / `chat_message` 已按同一方式解除（WP-024）：来源有源码/DDL 证据的列用别名或
落地区适配登记，缺来源的列仍保持 blocked——不为了计数放宽判定。

解除方式不改迁移：往生成器补有证据的别名/派生登记（落地区适配脚本与注册表同步生成），
把表模式改为 `copy`，重新生成并重跑 V11 即可。

### 解除 blocked 之前必须过的契约（WP-027，手工维护，非生成物）

`30-knowledge-import-contract.sql` + `31-knowledge-import-contract-selftest.sql` 是**手工维护的工具**
（生成器只写 10/20/V11，不会覆盖它们）：

- `knowledge_import_contract`：统一侧**每一列一条来源声明**（`source_kind` + `source_ref` + `evidence_ref`），
  覆盖 README 上表列出的 11 列。**没有 `evidence_ref` 的声明连登记都登记不进去**（表约束）。
- `knowledge_collection_prefix`：G-01 的真实 collection 前缀（`collection_name = prefix || kid`）。
- `knowledge_import_contract_check(p_synthetic)`：逐条校验覆盖完整性与可信性，并做
  **断关联 / 多义 / 跨租户 / NULL / 超宽** 检查，输出对账计数；任一项不满足即 `RAISE`，
  调用方在一个事务里跑 ⇒ 失败整体回滚。
- `31-…-selftest.sql`：**合成夹具**（`is_synthetic = true`、URL 用 `synthetic.invalid`、前缀用源码默认
  `LocalKnowledge`）+ **1 条正向 + 11 条负例**，证明校验器"该拒绝时真的拒绝"。
  **合成跑通 ≠ 真实签收**：真实模式（`p_synthetic = false`）拒绝任何合成声明，
  当前真实状态仍是 `blocked`（G-01/G-02 未提供）。

## 生成物与再生成

三个文件都由 `scripts/db/generate-legacy-ai-copy.py` 生成，**不要手改**：

```bash
python scripts/db/generate-legacy-ai-copy.py \
  ../mydocs/platform-embedded/03-table-map.json \
  services/platform/docs/script/sql/ruoyi-ai.sql \
  services/platform/docs/script/sql/postgres \
  services/platform/docs/script/sql/postgres/V11__legacy_ai_domain_data.sql \
  services/platform/docs/script/sql/legacy-mysql/10-legacy-ai-domain-staging.sql \
  services/platform/docs/script/sql/legacy-mysql/20-legacy-ai-domain-adaptation.sql
```

生成器从表映射与冻结迁移推导范围（V1..V6 已建的表属于平台基线，不归本迁移；`sj_*` 在统一
链里不存在，同样排除），并在生成时校验枚举映射仍然与两侧 Java 枚举一致——枚举漂移会让
生成失败，而不是让映射悄悄过期。

## 本机等价验证

本机无 Docker/psql 时用便携 PostgreSQL 18.6 真跑（见
`mydocs/platform-embedded/tools/local-pg/README.md` 的「旧 MySQL AI 域行迁移」一节）：
V1..V11 全链 + 落地区夹具 + `CheckLegacyAiData` 断言。容器侧（Flyway 记账、pgvector）
仍是 NOT_RUN。
