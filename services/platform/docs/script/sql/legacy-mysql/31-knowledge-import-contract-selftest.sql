-- ---------------------------------------------------------------------------
-- WP-027（T1）：输入契约自检 —— **合成夹具 + 负例矩阵**（手工维护工具，非 Flyway 迁移）
--
-- ⚠️ 本文件插入的每一行都是**合成样本**（`is_synthetic = true`、`evidence_ref` 以 `SYNTHETIC`
--    开头、URL 用 `https://synthetic.invalid/...`），**绝不代表任何真实部署的 collection 前缀
--    或真实对象登记**。它的唯一目的是证明 `30-knowledge-import-contract.sql` 的校验器
--    **在该拒绝时真的拒绝**（否则"契约"只是文档）。
--
-- 合成前缀用的是源码默认值 `LocalKnowledge`（`VectorStoreProperties:83`，拼接规则
-- `collectionname + kid` 见 `MilvusVectorStoreStrategy:91` / `QdrantVectorStoreStrategy:103`）
-- —— 这是"规则长什么样"的证据，**不是**任何真实部署的取值（D11）。
--
-- 真实签收状态：**blocked**（G-01 真实前缀、G-02 `oss_id → sys_oss` 来源）。
-- 本文件跑通**不能**解除 blocked；它只证明解除 blocked 之后校验器可用。
--
-- 负例矩阵（每条都必须**触发**且**理由正确**，否则本文件报错）：
--   N0 真实模式 + 空契约            → 缺少契约声明
--   N1 少一列声明                   → 缺少契约声明
--   N2 evidence_ref 为空            → 缺少 source_ref/evidence_ref
--   N3 合成声明跑真实模式           → 真实模式不接受合成声明
--   N4 附件 oss_id 查不到登记       → G-02 断关联
--   N5 同 (knowledge_id, doc_id) 指向两个对象 → G-02 多义
--   N6 登记行属于其它租户           → G-02 跨租户
--   N7 登记 URL 为空                → G-02 NULL
--   N8 prefix||kid 超过 VARCHAR(64) → G-01 超宽
--   N9 URL 超过 VARCHAR(1024)       → G-02 超宽
--   N10 校验失败后的**整体回滚**（子事务内改动全部消失）
--
-- 幂等：所有合成行按固定 id/主键范围（9700001..9700099）先删后插；结尾清理合成行，
-- 只删 `is_synthetic = true` 的声明与合成 id 范围，**不碰运维登记的真实行**。
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS legacy_mysql;

-- ---------------------------------------------------------------- 合成夹具（幂等播种 / 恢复）
CREATE OR REPLACE FUNCTION legacy_mysql.wp027_selftest_seed(p_synthetic boolean DEFAULT true)
RETURNS void
LANGUAGE plpgsql
AS $wp027_seed$
DECLARE
    v_evidence text := CASE WHEN p_synthetic THEN 'SYNTHETIC:WP-027 selftest（非真实来源）'
                            ELSE 'ops-ticket:WP-027-REAL（占位：真实签收时必须替换为真实来源清单）' END;
BEGIN
    -- 11 列声明（覆盖 README「为什么还有 blocked 表」的清单）
    INSERT INTO legacy_mysql.knowledge_import_contract
        (target_table, target_column, source_kind, source_ref, evidence_ref, is_synthetic, supplied_by, remark)
    VALUES
        ('ai_knowledge_base','collection_name','external_registry','knowledge_collection_prefix.prefix || knowledge_info.id', v_evidence, p_synthetic, 'wp027-selftest','G-01'),
        ('ai_knowledge_base','created_by','legacy_column','knowledge_info.create_by', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_base','owner_member_id','derived','canonical platform:<tenantId>:<userId>', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_document','kb_id','legacy_column','knowledge_info.id', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_document','doc_name','legacy_column','knowledge_attach.name', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_document','file_type','legacy_column','knowledge_attach.type', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_document','file_url','external_registry','sys_oss.url（按 knowledge_attach.oss_id 关联）', v_evidence, p_synthetic, 'wp027-selftest','G-02'),
        ('ai_knowledge_document','created_by','legacy_column','knowledge_attach.create_by', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_chunk','kb_id','legacy_column','knowledge_fragment.knowledge_id', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_chunk','chunk_index','legacy_column','knowledge_fragment.idx', v_evidence, p_synthetic, 'wp027-selftest',NULL),
        ('ai_knowledge_chunk','created_by','legacy_column','knowledge_fragment.create_by', v_evidence, p_synthetic, 'wp027-selftest',NULL)
    ON CONFLICT (target_table, target_column) DO UPDATE
        SET source_kind = EXCLUDED.source_kind,
            source_ref = EXCLUDED.source_ref,
            evidence_ref = EXCLUDED.evidence_ref,
            is_synthetic = EXCLUDED.is_synthetic,
            supplied_by = EXCLUDED.supplied_by,
            supplied_at = now(),
            remark = EXCLUDED.remark;

    INSERT INTO legacy_mysql.knowledge_collection_prefix (tenant_key, prefix, evidence_ref, is_synthetic, supplied_by)
    VALUES ('000000', 'LocalKnowledge', v_evidence, p_synthetic, 'wp027-selftest')
    ON CONFLICT (tenant_key) DO UPDATE
        SET prefix = EXCLUDED.prefix, evidence_ref = EXCLUDED.evidence_ref,
            is_synthetic = EXCLUDED.is_synthetic, supplied_at = now();

    -- 合成对象登记（平台 sys_oss）：URL 用 .invalid 保留域，明示不可达/非真实
    DELETE FROM platform.sys_oss WHERE oss_id BETWEEN 9700001 AND 9700099;
    INSERT INTO platform.sys_oss (oss_id, tenant_id, file_name, original_name, file_suffix, url, service)
    VALUES (9700001, '000000', 'synthetic-a.pdf', 'synthetic-a.pdf', 'pdf', 'https://synthetic.invalid/wp027/a.pdf', 'minio'),
           (9700002, '000000', 'synthetic-b.pdf', 'synthetic-b.pdf', 'pdf', 'https://synthetic.invalid/wp027/b.pdf', 'minio');

    -- 合成附件行（knowledge_attach 无主键约束 → 先删后插）
    DELETE FROM legacy_mysql.knowledge_attach WHERE id BETWEEN 9700001 AND 9700099;
    INSERT INTO legacy_mysql.knowledge_attach (id, knowledge_id, oss_id, doc_id, name, type, tenant_id, status, create_by)
    VALUES (9700001, 9700001, 9700001, 'doc-wp027-a', 'synthetic-a.pdf', 'pdf', 0, 0, 'wp027-selftest'),
           (9700002, 9700001, 9700002, 'doc-wp027-b', 'synthetic-b.pdf', 'pdf', 0, 0, 'wp027-selftest');

    -- 合成知识库行：G-01 的 prefix||kid 宽度判据必须有真实的行可算，否则"≤64"是空话
    DELETE FROM legacy_mysql.knowledge_info WHERE id BETWEEN 9700001 AND 9700099;
    INSERT INTO legacy_mysql.knowledge_info (id, user_id, name, tenant_id, create_by)
    VALUES (9700001, 11, 'synthetic-kb-wp027', 0, 'wp027-selftest');
END
$wp027_seed$;

-- ---------------------------------------------------------------- 断言：必须拒绝且理由正确
CREATE OR REPLACE FUNCTION legacy_mysql.wp027_selftest_assert_refusal(
    p_case text, p_token text, p_synthetic boolean)
RETURNS text
LANGUAGE plpgsql
AS $wp027_assert$
DECLARE
    v_err text;
BEGIN
    BEGIN
        PERFORM r FROM legacy_mysql.knowledge_import_contract_check(p_synthetic) r;
        RAISE EXCEPTION 'WP-027 自检失败：% 没有触发拒绝（校验器在该拒绝时通过了）', p_case;
    EXCEPTION WHEN OTHERS THEN
        v_err := SQLERRM;
        IF position('WP-027 自检失败：' in v_err) > 0 THEN
            RAISE;
        END IF;
    END;
    IF position(p_token in v_err) = 0 THEN
        RAISE EXCEPTION 'WP-027 自检失败：% 触发了拒绝但理由不对（期望包含「%」，实际：%）', p_case, p_token, v_err;
    END IF;
    RAISE NOTICE '% OK：%', p_case, left(v_err, 150);
    RETURN v_err;
END
$wp027_assert$;

-- ---------------------------------------------------------------- N0：真实模式 + 空契约 → 必须拒绝
DO $n0$
BEGIN
    DELETE FROM legacy_mysql.knowledge_import_contract WHERE is_synthetic = true;
    DELETE FROM legacy_mysql.knowledge_collection_prefix WHERE is_synthetic = true;
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N0 真实模式/空契约', '缺少契约声明', false);
    RAISE NOTICE 'WP-027 当前真实签收状态：blocked（无任何真实契约声明）—— N0 证明了这一点';
END
$n0$;

-- ---------------------------------------------------------------- 正向：合成模式必须通过并给出对账报告
DO $positive$
DECLARE
    r record;
    n int := 0;
    v_contract bigint := -1;
    v_prefix bigint := -1;
    v_attach_oss bigint := -1;
    v_kb bigint := -1;
    v_fixture_oss bigint;
    v_fixture_attach bigint;
    v_fixture_kb bigint;
BEGIN
    PERFORM legacy_mysql.wp027_selftest_seed(true);

    -- G-37/H-19 自查（夹具锚点）：合成夹具必须真的落库，否则"G-01/G-02 全部可解析"
    -- 是**空集合上的恒真断言**（附件 0 行时，五个 G-02 检查都会"通过"）。
    SELECT count(*) INTO v_fixture_oss FROM platform.sys_oss WHERE oss_id BETWEEN 9700001 AND 9700099;
    SELECT count(*) INTO v_fixture_attach FROM legacy_mysql.knowledge_attach
     WHERE id BETWEEN 9700001 AND 9700099 AND oss_id IS NOT NULL;
    SELECT count(*) INTO v_fixture_kb FROM legacy_mysql.knowledge_info WHERE id BETWEEN 9700001 AND 9700099;
    IF v_fixture_oss <> 2 OR v_fixture_attach <> 2 OR v_fixture_kb <> 1 THEN
        RAISE EXCEPTION 'WP-027 自检失败：合成夹具未按预期落库（sys_oss=% attach=% knowledge_info=%）'
            '—— 夹具为空时"全部可解析"没有意义', v_fixture_oss, v_fixture_attach, v_fixture_kb;
    END IF;

    FOR r IN SELECT * FROM legacy_mysql.knowledge_import_contract_check(true) LOOP
        n := n + 1;
        IF r.item = 'contract_rows' THEN v_contract := r.count_value; END IF;
        IF r.item = 'collection_prefix_rows' THEN v_prefix := r.count_value; END IF;
        IF r.item = 'attach_with_oss' THEN v_attach_oss := r.count_value; END IF;
        IF r.item = 'knowledge_info_rows' THEN v_kb := r.count_value; END IF;
        RAISE NOTICE 'POSITIVE 对账：% = %（%）', r.item, r.count_value, r.note;
    END LOOP;

    -- G-37/H-19 自查（分支覆盖）：-1 表示报告里根本没有这一项 ⇒ 对应检查段被跳过。
    -- 只断言"报告行数 ≥ 6"是不够的：报告尾部的对账计数是无条件产生的，那才是恒真。
    IF v_contract <> 11 OR v_prefix <> 1 OR v_attach_oss <> 2 OR v_kb < 1 THEN
        RAISE EXCEPTION 'WP-027 自检失败：正向报告不符合预期（contract=% prefix=% attach_with_oss=% knowledge_info=%）；'
            '-1 表示该检查段没执行（被跳过）', v_contract, v_prefix, v_attach_oss, v_kb;
    END IF;
    IF n < 6 THEN
        RAISE EXCEPTION 'WP-027 自检失败：正向对账报告行数不足（% < 6）', n;
    END IF;
    RAISE NOTICE 'POSITIVE OK：合成模式下契约完整、G-01/G-02 均可解析（合成样本，非真实签收）';
END
$positive$;

-- ---------------------------------------------------------------- N1：少一列声明
DO $n1$
BEGIN
    DELETE FROM legacy_mysql.knowledge_import_contract
     WHERE target_table = 'ai_knowledge_chunk' AND target_column = 'chunk_index';
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N1 缺一列声明', '缺少契约声明', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n1$;

-- ---------------------------------------------------------------- N2：没有来源证明的声明**登记不进去**
-- 事实：`ck_knowledge_import_contract_evidence` 在表上就拒绝空白 evidence_ref（比函数里的检查更早）。
-- 因此函数内的"空白 source_ref/evidence_ref"分支是**约束被绕过时的防御**，当前不可达 ——
-- 这里证明约束真的会拒绝，并断言约束存在（而不是假装函数分支被触发过）。
DO $n2$
DECLARE
    v_err text;
    v_has_constraint boolean;
BEGIN
    BEGIN
        UPDATE legacy_mysql.knowledge_import_contract SET evidence_ref = '   '
         WHERE target_table = 'ai_knowledge_document' AND target_column = 'file_url';
        RAISE EXCEPTION 'WP-027 自检失败：N2 空白 evidence_ref 居然被接受';
    EXCEPTION WHEN check_violation THEN
        v_err := SQLERRM;
    END;
    IF position('ck_knowledge_import_contract_evidence' in v_err) = 0 THEN
        RAISE EXCEPTION 'WP-027 自检失败：N2 拒绝理由不对（实际：%）', v_err;
    END IF;
    SELECT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_knowledge_import_contract_evidence') INTO v_has_constraint;
    IF NOT v_has_constraint THEN
        RAISE EXCEPTION 'WP-027 自检失败：N2 依赖的约束 ck_knowledge_import_contract_evidence 不存在';
    END IF;
    RAISE NOTICE 'N2 OK：空白来源证明在**表约束**层就被拒绝（SQLSTATE=23514，%）；'
                 '函数内的同名检查保留为约束被绕过时的防御', left(v_err, 80);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n2$;

-- ---------------------------------------------------------------- N3：合成声明跑真实模式
DO $n3$
BEGIN
    PERFORM legacy_mysql.wp027_selftest_seed(true);
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N3 合成声明/真实模式', '真实模式不接受合成声明', false);
END
$n3$;

-- ---------------------------------------------------------------- N4：断关联
DO $n4$
BEGIN
    DELETE FROM platform.sys_oss WHERE oss_id = 9700002;
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N4 断关联', 'G-02 断关联', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n4$;

-- ---------------------------------------------------------------- N5：多义（同一文档身份指向两个对象）
DO $n5$
BEGIN
    INSERT INTO platform.sys_oss (oss_id, tenant_id, file_name, original_name, file_suffix, url, service)
    VALUES (9700003, '000000', 'synthetic-a-dup.pdf', 'synthetic-a-dup.pdf', 'pdf', 'https://synthetic.invalid/wp027/a-dup.pdf', 'minio');
    INSERT INTO legacy_mysql.knowledge_attach (id, knowledge_id, oss_id, doc_id, name, type, tenant_id, status)
    VALUES (9700003, 9700001, 9700003, 'doc-wp027-a', 'synthetic-a-dup.pdf', 'pdf', 0, 0);
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N5 多义', 'G-02 多义', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n5$;

-- ---------------------------------------------------------------- N6：跨租户
DO $n6$
BEGIN
    UPDATE platform.sys_oss SET tenant_id = '999999' WHERE oss_id = 9700001;
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N6 跨租户', 'G-02 跨租户', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n6$;

-- ---------------------------------------------------------------- N7：登记 URL 为空
DO $n7$
BEGIN
    UPDATE platform.sys_oss SET url = '   ' WHERE oss_id = 9700001;
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N7 NULL 来源', 'G-02 NULL', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n7$;

-- ---------------------------------------------------------------- N8：collection_name 超宽
DO $n8$
BEGIN
    UPDATE legacy_mysql.knowledge_collection_prefix SET prefix = repeat('c', 61) WHERE tenant_key = '000000';
    PERFORM legacy_mysql.wp027_selftest_assert_refusal('N8 collection 超宽', 'G-01 超宽', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n8$;

-- ---------------------------------------------------------------- N9：file_url 超宽 —— **可达性锚点**
-- 事实：当前来源 `sys_oss.url` 是 varchar(500)，目标 `ai_knowledge_document.file_url` 是
-- varchar(1024)（V7:204）。**窄源不可能喂宽目标**，所以经 sys_oss 关联这条路径上
-- ">1024" 分支当前**不可达** —— 与其造一个假的超宽样本，不如把这条可达性关系钉住：
-- 一旦有人把 sys_oss.url 放宽到 1024 以上（或换用更宽的登记列），本断言失败，
-- 提示"函数里那条 >1024 检查从此刻起变成可达路径，需要真实负例"。
DO $n9$
DECLARE
    v_src int;
    v_dst int;
BEGIN
    SELECT character_maximum_length INTO v_src
      FROM information_schema.columns
     WHERE table_schema = 'platform' AND table_name = 'sys_oss' AND column_name = 'url';
    SELECT character_maximum_length INTO v_dst
      FROM information_schema.columns
     WHERE table_schema = 'platform' AND table_name = 'ai_knowledge_document' AND column_name = 'file_url';
    IF v_src IS NULL OR v_dst IS NULL THEN
        RAISE EXCEPTION 'WP-027 自检失败：N9 取不到列宽（sys_oss.url=%，file_url=%）', v_src, v_dst;
    END IF;
    IF v_src > 1024 THEN
        RAISE EXCEPTION 'WP-027 自检失败：N9 可达性变了 —— sys_oss.url 已放宽到 %（>1024），'
                        '请补一条真实的超宽负例再继续', v_src;
    END IF;
    IF v_dst <> 1024 THEN
        RAISE EXCEPTION 'WP-027 自检失败：N9 目标列宽变了（file_url=%），契约里的宽度判据需要复核', v_dst;
    END IF;
    RAISE NOTICE 'N9 OK：窄源（sys_oss.url=%）< 宽目标（file_url=%）⇒ 经该来源的 file_url 超宽不可达；'
                 '函数内的 >1024 检查保留为"换用更宽登记列"时的防御', v_src, v_dst;
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n9$;

-- ---------------------------------------------------------------- N10：失败即整体回滚
DO $n10$
DECLARE
    v_err text;
BEGIN
    BEGIN
        -- 子事务内的两处改动：一条与 11 列无关的标记行 + 把前缀改成超宽
        INSERT INTO legacy_mysql.knowledge_import_contract
            (target_table, target_column, source_kind, source_ref, evidence_ref, is_synthetic, remark)
        VALUES ('ai_knowledge_base','name','legacy_column','knowledge_info.name',
                'SYNTHETIC:WP-027 rollback probe', true, 'N10 marker');
        UPDATE legacy_mysql.knowledge_collection_prefix SET prefix = repeat('y', 61) WHERE tenant_key = '000000';
        PERFORM r FROM legacy_mysql.knowledge_import_contract_check(true) r;
        RAISE EXCEPTION 'WP-027 自检失败：N10 没有触发拒绝';
    EXCEPTION WHEN OTHERS THEN
        v_err := SQLERRM;
    END;
    IF position('G-01 超宽' in v_err) = 0 THEN
        RAISE EXCEPTION 'WP-027 自检失败：N10 理由不对（实际：%）', v_err;
    END IF;
    IF EXISTS (SELECT 1 FROM legacy_mysql.knowledge_import_contract
                WHERE target_table = 'ai_knowledge_base' AND target_column = 'name') THEN
        RAISE EXCEPTION 'WP-027 自检失败：N10 失败回滚未生效（标记行仍在）';
    END IF;
    IF EXISTS (SELECT 1 FROM legacy_mysql.knowledge_collection_prefix
                WHERE tenant_key = '000000' AND prefix <> 'LocalKnowledge') THEN
        RAISE EXCEPTION 'WP-027 自检失败：N10 失败回滚未生效（前缀改动仍在）';
    END IF;
    RAISE NOTICE 'N10 OK：校验失败后子事务整体回滚（标记行与前缀改动都已消失）—— 调用方一个事务内跑即"失败不留半成品"';
END
$n10$;

-- ---------------------------------------------------------------- N11/N12：**适用性**不可被静默跳过
-- 事实：G-01/G-02 两段检查原本用 `IF EXISTS (... source_kind='external_registry')` 做门，
-- 只要把这一列的 source_kind 改声明成别的，整段检查就会**静默消失**（H-19 同族：判据的作用域
-- 由数据决定）。30-…-contract.sql 现在把"适用性"本身变成断言，这两条负例证明它真的会响。
DO $n11$
BEGIN
    UPDATE legacy_mysql.knowledge_import_contract
       SET source_kind = 'derived', source_ref = 'guessed-from-nowhere'
     WHERE target_table = 'ai_knowledge_base' AND target_column = 'collection_name';
    PERFORM legacy_mysql.wp027_selftest_assert_refusal(
        'N11 G-01 适用性', 'G-01：collection_name 只能来自部署配置', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n11$;

DO $n12$
BEGIN
    UPDATE legacy_mysql.knowledge_import_contract
       SET source_kind = 'derived', source_ref = 'guessed-from-nowhere'
     WHERE target_table = 'ai_knowledge_document' AND target_column = 'file_url';
    PERFORM legacy_mysql.wp027_selftest_assert_refusal(
        'N12 G-02 适用性', 'G-02：file_url 只能来自平台对象登记', true);
    PERFORM legacy_mysql.wp027_selftest_seed(true);
END
$n12$;

-- ---------------------------------------------------------------- 收尾：清掉合成行，恢复落地区
DO $cleanup$
DECLARE
    v_left bigint;
    v_residue text := '';
BEGIN
    DELETE FROM legacy_mysql.knowledge_import_contract WHERE is_synthetic = true;
    DELETE FROM legacy_mysql.knowledge_collection_prefix WHERE is_synthetic = true;
    DELETE FROM legacy_mysql.knowledge_attach WHERE id BETWEEN 9700001 AND 9700099;
    DELETE FROM legacy_mysql.knowledge_info WHERE id BETWEEN 9700001 AND 9700099;
    DELETE FROM platform.sys_oss WHERE oss_id BETWEEN 9700001 AND 9700099;

    -- G-37/H-19 自查：五个来源都要核对，不能只查一个就宣布"清理干净"
    SELECT count(*) INTO v_left FROM legacy_mysql.knowledge_import_contract WHERE is_synthetic = true;
    IF v_left > 0 THEN v_residue := v_residue || format(' contract=%s', v_left); END IF;
    SELECT count(*) INTO v_left FROM legacy_mysql.knowledge_collection_prefix WHERE is_synthetic = true;
    IF v_left > 0 THEN v_residue := v_residue || format(' prefix=%s', v_left); END IF;
    SELECT count(*) INTO v_left FROM legacy_mysql.knowledge_attach WHERE id BETWEEN 9700001 AND 9700099;
    IF v_left > 0 THEN v_residue := v_residue || format(' attach=%s', v_left); END IF;
    SELECT count(*) INTO v_left FROM legacy_mysql.knowledge_info WHERE id BETWEEN 9700001 AND 9700099;
    IF v_left > 0 THEN v_residue := v_residue || format(' knowledge_info=%s', v_left); END IF;
    SELECT count(*) INTO v_left FROM platform.sys_oss WHERE oss_id BETWEEN 9700001 AND 9700099;
    IF v_left > 0 THEN v_residue := v_residue || format(' sys_oss=%s', v_left); END IF;

    IF v_residue <> '' THEN
        RAISE EXCEPTION 'WP-027 自检收尾失败：合成行残留（%）', v_residue;
    END IF;
    RAISE NOTICE 'WP-027 自检完成：1 条正向 + 13 条负例全部按预期触发'
                 '（N2 在表约束层、N9 为可达性锚点、N11/N12 为适用性锚点，见各自说明），'
                 '五类合成行（声明/前缀/附件/知识库/对象登记）全部清理为零。'
                 '真实签收仍为 blocked（G-01 真实 collection 前缀、G-02 oss_id→sys_oss 来源未提供）——'
                 '本次运行不构成真实签收证据。';
END
$cleanup$;
