-- ---------------------------------------------------------------------------
-- WP-027（T1）：知识域旧来源**输入契约 + 来源校验**（落地区工具，非 Flyway 迁移）
--
-- 本文件是**手工维护**的工具，不是 `scripts/db/generate-legacy-ai-copy.py` 的产物
-- （生成器只写 10/20/V11 三个文件；见同目录 README「生成物与再生成」）。
--
-- ## 为什么需要它
--
-- `knowledge_info` / `knowledge_attach` / `knowledge_fragment` 三张落地区表在 V11 里登记为
-- **blocked**（见 README「为什么还有 blocked 表」）：统一侧 `ai_knowledge_base` /
-- `ai_knowledge_document` / `ai_knowledge_chunk` 有 11 个**平台侧列**，旧 MySQL 行没有同名列，
-- 取值要么来自部署配置、要么来自平台登记表。**迁移脚本臆造这些值等于伪造数据**（D11），
-- 所以它们在拿到可信事实之前不许被"猜"出来。
--
-- 本文件把"拿到可信事实"这件事变成**可机检的契约**：
--   1. 运维/DBA 把两项事实登记进 `knowledge_import_contract` / `knowledge_collection_prefix`
--      —— **每一列一条声明**，写明 `source_kind`（legacy_column / derived / external_registry）、
--      `source_ref`（具体旧列名、派生规则 id、或登记表.列）与 `evidence_ref`（这条事实从哪来：
--      配置键路径、运维工单、DDL 文件路径…）。**没有 evidence_ref 的声明一律拒绝**；
--   2. `knowledge_import_contract_check()` 逐条校验：覆盖是否完整、来源是否可信、
--      两项外部事实是否真的可用（断关联 / 多义 / 跨租户 / NULL / 超宽），并输出对账计数；
--   3. 任一项不满足即 `RAISE EXCEPTION` —— 调用方在**一个事务**里跑，失败即整体回滚
--      （与 `20-legacy-ai-domain-adaptation.sql` 同一纪律）。
--
-- ## 合成模式（D11）
--
-- 源码默认前缀是 `LocalKnowledge`（`VectorStoreProperties:83`），
-- 拼接规则是 `collectionname + kid`（`MilvusVectorStoreStrategy:91`、`QdrantVectorStoreStrategy:103`）。
-- **这只能证明"规则长什么样"，不能证明任何真实部署的前缀**。因此：
--   * `p_synthetic = true`：允许 `is_synthetic = true` 的声明，用于合成验收与工具自检；
--     函数会打印醒目的 NOTICE 说明"这不是真实签收"；
--   * `p_synthetic = false`：**拒绝任何** `is_synthetic = true` 的声明，且 `evidence_ref`
--     不得以 `SYNTHETIC` 开头 —— 真实签收必须给真实来源清单。
--
-- 真实签收状态：**blocked**（G-01 旧 vector collection 前缀、G-02 `oss_id → sys_oss` 来源）。
-- 本文件不改变这一点，只是让"什么时候可以解除 blocked"变成一条可执行的判据。
--
-- ## 覆盖的 11 列（来源：同目录 README「为什么还有 blocked 表」的表）
--   ai_knowledge_base.collection_name / created_by / owner_member_id
--   ai_knowledge_document.kb_id / doc_name / file_type / file_url / created_by
--   ai_knowledge_chunk.kb_id / chunk_index / created_by
-- 该清单变更必须**同时**改本文件与 `31-…-selftest.sql` 的数量锚点，否则自检失败。
--
-- ## 幂等
-- 两个登记表用 `CREATE TABLE IF NOT EXISTS`；校验函数 `CREATE OR REPLACE`；
-- 本文件**不改任何统一表、不写 sys_oss、不写落地区业务行**。
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS legacy_mysql;

-- ---------------------------------------------------------------- 登记表（运维填写）
CREATE TABLE IF NOT EXISTS legacy_mysql.knowledge_import_contract (
    target_table   varchar(64) NOT NULL,
    target_column  varchar(64) NOT NULL,
    source_kind    varchar(32) NOT NULL,
    source_ref     varchar(256) NOT NULL,
    evidence_ref   varchar(512) NOT NULL,
    is_synthetic   boolean     NOT NULL DEFAULT false,
    supplied_by    varchar(64) NOT NULL DEFAULT current_user,
    supplied_at    timestamptz NOT NULL DEFAULT now(),
    remark         varchar(500),
    CONSTRAINT pk_knowledge_import_contract PRIMARY KEY (target_table, target_column),
    CONSTRAINT ck_knowledge_import_contract_kind
        CHECK (source_kind IN ('legacy_column', 'derived', 'external_registry')),
    CONSTRAINT ck_knowledge_import_contract_source CHECK (btrim(source_ref) <> ''),
    CONSTRAINT ck_knowledge_import_contract_evidence CHECK (btrim(evidence_ref) <> '')
);

COMMENT ON TABLE legacy_mysql.knowledge_import_contract IS
    'WP-027 知识域导入输入契约：统一侧每一列取值的来源声明（无 evidence_ref 即不可用）';

CREATE TABLE IF NOT EXISTS legacy_mysql.knowledge_collection_prefix (
    tenant_key    varchar(64) NOT NULL,   -- 落地区租户键（旧库数值租户按文本；平台默认租户写 000000）
    prefix        varchar(64) NOT NULL,   -- vector-store.<store>.collectionname 的真实取值
    evidence_ref  varchar(512) NOT NULL,  -- 配置键路径 / 运维工单 / 部署清单
    is_synthetic  boolean     NOT NULL DEFAULT false,
    supplied_by   varchar(64) NOT NULL DEFAULT current_user,
    supplied_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_collection_prefix PRIMARY KEY (tenant_key),
    CONSTRAINT ck_knowledge_collection_prefix_evidence CHECK (btrim(evidence_ref) <> '')
);

COMMENT ON TABLE legacy_mysql.knowledge_collection_prefix IS
    'WP-027 G-01：真实 collection 前缀（collection_name = prefix || kid，kid=知识库 id）';

-- ---------------------------------------------------------------- 校验函数
CREATE OR REPLACE FUNCTION legacy_mysql.knowledge_import_contract_check(
    p_synthetic boolean DEFAULT false,
    p_verbose   boolean DEFAULT true)
RETURNS TABLE (item text, count_value bigint, note text)
LANGUAGE plpgsql
AS $wp027_contract$
DECLARE
    -- 来源：同目录 README「为什么还有 blocked 表」；变更需同步改 31-…-selftest.sql 的锚点
    v_required constant text[] := ARRAY[
        'ai_knowledge_base.collection_name',
        'ai_knowledge_base.created_by',
        'ai_knowledge_base.owner_member_id',
        'ai_knowledge_document.kb_id',
        'ai_knowledge_document.doc_name',
        'ai_knowledge_document.file_type',
        'ai_knowledge_document.file_url',
        'ai_knowledge_document.created_by',
        'ai_knowledge_chunk.kb_id',
        'ai_knowledge_chunk.chunk_index',
        'ai_knowledge_chunk.created_by'];

    v_missing text[];
    v_bad text;
    v_count bigint;
    v_samples text;
    v_prefix_count bigint;
    v_kid record;
    v_attach record;
    v_hits bigint;
    v_oss_tenant text;
    v_url text;
    v_kind_text text;
    v_mode text := CASE WHEN p_synthetic THEN 'SYNTHETIC（合成验收，不是真实签收）' ELSE 'REAL（真实签收）' END;
BEGIN
    -- ---------------------------------------------------------------- 0) 前置
    IF to_regclass('legacy_mysql.knowledge_import_contract') IS NULL
       OR to_regclass('legacy_mysql.knowledge_collection_prefix') IS NULL THEN
        RAISE EXCEPTION 'WP-027 契约：登记表缺失，请先执行 30-knowledge-import-contract.sql';
    END IF;
    IF to_regclass('legacy_mysql.knowledge_attach') IS NULL
       OR to_regclass('legacy_mysql.knowledge_info') IS NULL THEN
        RAISE EXCEPTION 'WP-027 契约：落地区表缺失（knowledge_info / knowledge_attach），请先执行 10-legacy-ai-domain-staging.sql';
    END IF;

    -- ---------------------------------------------------------------- 1) 覆盖：11 列每列一条声明
    -- G-37/H-19 自查：清单长度本身也要锚住。README 表变了而这里没变，或反之，
    -- 都会让"覆盖完整"这句话失去意义 —— 所以先钉住数量，再谈覆盖。
    IF cardinality(v_required) <> 11 THEN
        RAISE EXCEPTION 'WP-027 契约：required 清单与 README 的 11 列不一致（实际 %）——'
            '改清单必须同步改 31-…-selftest.sql 的夹具锚点，不允许静默漂移',
            cardinality(v_required);
    END IF;
    SELECT array_agg(req ORDER BY req) INTO v_missing
      FROM unnest(v_required) req
     WHERE NOT EXISTS (SELECT 1 FROM legacy_mysql.knowledge_import_contract c
                        WHERE c.target_table || '.' || c.target_column = req);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'WP-027 契约：缺少契约声明（% 列）：%。缺来源的列不得靠猜——要么补可信来源，要么继续 blocked',
            cardinality(v_missing), array_to_string(v_missing, ', ');
    END IF;

    -- ---------------------------------------------------------------- 2) 声明自身的可信性
    SELECT string_agg(c.target_table || '.' || c.target_column || '（' || c.source_kind || '）', ', ')
      INTO v_bad
      FROM legacy_mysql.knowledge_import_contract c
     WHERE btrim(c.source_ref) = ''
        OR btrim(c.evidence_ref) = ''
        OR (p_synthetic = false AND c.is_synthetic = true)
        OR (p_synthetic = false AND c.evidence_ref ILIKE 'SYNTHETIC%');
    IF v_bad IS NOT NULL THEN
        IF p_synthetic THEN
            RAISE EXCEPTION 'WP-027 契约：声明缺少 source_ref/evidence_ref：%', v_bad;
        END IF;
        RAISE EXCEPTION 'WP-027 契约：真实模式不接受合成声明（is_synthetic=true 或 evidence_ref 以 SYNTHETIC 开头）：%', v_bad;
    END IF;

    SELECT count(*) INTO v_count FROM legacy_mysql.knowledge_import_contract;
    item := 'contract_rows'; count_value := v_count;
    note := format('已声明 %s 列（应为 %s）；模式=%s', v_count, cardinality(v_required), v_mode);
    RETURN NEXT;

    -- ---------------------------------------------------------------- 3) G-01：collection 前缀 + 超宽
    -- G-37/H-19 自查：整段检查原本用 `IF EXISTS (... source_kind='external_registry')` 做门 ——
    -- 只要有人把这一列改声明成 derived/legacy_column，整段 G-01 检查就会**静默消失**。
    -- 事实是这一列的取值只能来自部署配置（`vector-store.*.collectionname + kid`），
    -- 声明成别的就是"在猜"，所以这里把**适用性本身**变成断言，再无条件执行检查。
    SELECT c.source_kind INTO v_kind_text
      FROM legacy_mysql.knowledge_import_contract c
     WHERE c.target_table = 'ai_knowledge_base' AND c.target_column = 'collection_name';
    IF v_kind_text IS DISTINCT FROM 'external_registry' THEN
        RAISE EXCEPTION 'WP-027 G-01：collection_name 只能来自部署配置（vector-store.*.collectionname + kid），'
            '声明为 % 等于在猜；本校验拒绝"改个 source_kind 就把 G-01 检查跳过去"',
            coalesce(v_kind_text, '<缺失>');
    END IF;
    BEGIN
        SELECT count(*) INTO v_prefix_count FROM legacy_mysql.knowledge_collection_prefix;
        IF v_prefix_count = 0 THEN
            RAISE EXCEPTION 'WP-027 G-01：collection_name 声明为 external_registry，但 knowledge_collection_prefix 没有任何前缀事实（blocked：缺部署配置）';
        END IF;
        IF EXISTS (SELECT 1 FROM legacy_mysql.knowledge_collection_prefix p WHERE btrim(p.prefix) = '') THEN
            RAISE EXCEPTION 'WP-027 G-01：collection 前缀为空值（NULL/空白前缀不可用）';
        END IF;

        -- 超宽：prefix || kid 必须放进 ai_knowledge_base.collection_name VARCHAR(64)（V7:187）
        SELECT count(*), string_agg(DISTINCT left(p.prefix || i.id::text, 80), ', ')
          INTO v_count, v_samples
          FROM legacy_mysql.knowledge_info i
          LEFT JOIN legacy_mysql.knowledge_collection_prefix p
                 ON p.tenant_key IN (i.tenant_id::text, CASE WHEN i.tenant_id = 0 THEN '000000' ELSE i.tenant_id::text END)
         WHERE p.prefix IS NULL OR length(p.prefix || i.id::text) > 64;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-01 超宽/缺前缀：% 个知识库的 collection_name 无法落进 VARCHAR(64) 或找不到前缀；样本：%。不截断、不猜前缀',
                v_count, coalesce(v_samples, '<none>');
        END IF;
        item := 'collection_prefix_rows'; count_value := v_prefix_count;
        note := 'G-01 前缀事实齐备，且 prefix||kid 全部 ≤ 64';
        RETURN NEXT;
    END;

    -- ---------------------------------------------------------------- 4) G-02：oss_id → sys_oss
    -- 与 G-01 同样的自查：file_url 的来源只能是平台对象登记（旧 knowledge_attach.oss_id → sys_oss.url），
    -- 声明成别的就等于猜；把适用性变成断言，拒绝"改 source_kind 静默跳过 G-02"。
    SELECT c.source_kind INTO v_kind_text
      FROM legacy_mysql.knowledge_import_contract c
     WHERE c.target_table = 'ai_knowledge_document' AND c.target_column = 'file_url';
    IF v_kind_text IS DISTINCT FROM 'external_registry' THEN
        RAISE EXCEPTION 'WP-027 G-02：file_url 只能来自平台对象登记（knowledge_attach.oss_id → sys_oss.url），'
            '声明为 % 等于在猜；本校验拒绝"改个 source_kind 就把 G-02 检查跳过去"',
            coalesce(v_kind_text, '<缺失>');
    END IF;
    BEGIN
        IF to_regclass('platform.sys_oss') IS NULL AND to_regclass('sys_oss') IS NULL THEN
            RAISE EXCEPTION 'WP-027 G-02：file_url 声明为 external_registry，但平台登记表 sys_oss 不存在（blocked：缺对象登记来源）';
        END IF;

        -- 断关联：落地区有 oss_id 但登记表查不到
        SELECT count(*), string_agg(DISTINCT a.oss_id::text, ', ')
          INTO v_count, v_samples
          FROM legacy_mysql.knowledge_attach a
         WHERE a.oss_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM sys_oss o WHERE o.oss_id = a.oss_id);
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-02 断关联：% 条附件引用的 oss_id 在 sys_oss 里不存在；样本：%',
                v_count, coalesce(v_samples, '<none>');
        END IF;

        -- NULL：解析出来的 URL 为空
        SELECT count(*), string_agg(DISTINCT a.id::text, ', ')
          INTO v_count, v_samples
          FROM legacy_mysql.knowledge_attach a
          JOIN sys_oss o ON o.oss_id = a.oss_id
         WHERE btrim(coalesce(o.url, '')) = '';
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-02 NULL：% 条附件的 sys_oss.url 为空/空白；样本（附件 id）：%',
                v_count, coalesce(v_samples, '<none>');
        END IF;

        -- 跨租户：登记行租户与附件租户不一致（旧库数值 0 与平台默认租户 000000 视为同一默认租户）
        SELECT count(*), string_agg(DISTINCT a.id::text, ', ')
          INTO v_count, v_samples
          FROM legacy_mysql.knowledge_attach a
          JOIN sys_oss o ON o.oss_id = a.oss_id
         WHERE btrim(o.tenant_id) <> (CASE WHEN a.tenant_id = 0 THEN '000000' ELSE a.tenant_id::text END);
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-02 跨租户：% 条附件的 oss 登记行属于其它租户；样本（附件 id）：%',
                v_count, coalesce(v_samples, '<none>');
        END IF;

        -- 超宽：file_url 必须放进 VARCHAR(1024)（V7:204）
        SELECT count(*), string_agg(DISTINCT left(o.url, 80), ', ')
          INTO v_count, v_samples
          FROM legacy_mysql.knowledge_attach a
          JOIN sys_oss o ON o.oss_id = a.oss_id
         WHERE length(o.url) > 1024;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-02 超宽：% 条 oss.url 超过 ai_knowledge_document.file_url VARCHAR(1024)；样本：%。不截断',
                v_count, coalesce(v_samples, '<none>');
        END IF;

        -- 多义：同一 (knowledge_id, doc_id) 指向多个不同对象
        SELECT count(*) INTO v_count FROM (
            SELECT a.knowledge_id, a.doc_id
              FROM legacy_mysql.knowledge_attach a
             WHERE a.oss_id IS NOT NULL
             GROUP BY a.knowledge_id, a.doc_id
            HAVING count(DISTINCT a.oss_id) > 1) d;
        IF v_count > 0 THEN
            RAISE EXCEPTION 'WP-027 G-02 多义：% 组 (knowledge_id, doc_id) 指向多个不同 oss 对象，文档身份不唯一',
                v_count;
        END IF;

        SELECT count(*) INTO v_count
          FROM legacy_mysql.knowledge_attach a WHERE a.oss_id IS NOT NULL;
        item := 'attach_with_oss'; count_value := v_count;
        note := 'G-02 全部可解析（关联/唯一/租户/宽度/NULL 均通过）';
        RETURN NEXT;
    END;

    -- ---------------------------------------------------------------- 5) 对账计数
    SELECT count(*) INTO v_count FROM legacy_mysql.knowledge_info;
    item := 'knowledge_info_rows'; count_value := v_count; note := '落地区知识库行'; RETURN NEXT;
    SELECT count(*) INTO v_count FROM legacy_mysql.knowledge_attach;
    item := 'knowledge_attach_rows'; count_value := v_count; note := '落地区附件行'; RETURN NEXT;
    SELECT count(*) INTO v_count FROM legacy_mysql.knowledge_attach WHERE oss_id IS NULL;
    item := 'attach_without_oss'; count_value := v_count;
    note := '无 oss_id 的附件：file_url 无来源，属"不可恢复/需人工决定"，不得编造'; RETURN NEXT;

    IF p_verbose THEN
        RAISE NOTICE 'WP-027 契约校验通过：模式=%（% 列声明已覆盖；G-01/G-02 均已登记并校验）',
            v_mode, cardinality(v_required);
    END IF;
END
$wp027_contract$;

COMMENT ON FUNCTION legacy_mysql.knowledge_import_contract_check(boolean, boolean) IS
    'WP-027：校验知识域导入契约（覆盖/可信性/G-01 前缀/G-02 oss 关联、多义、跨租户、NULL、超宽），失败即 RAISE（调用方事务整体回滚）';
