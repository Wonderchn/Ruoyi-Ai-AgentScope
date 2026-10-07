-- V29：知识库文档的乐观锁令牌从 update_time 毫秒改为行内计数器
--
-- 背景（RW-04 §11.8，T0 采纳后正式分配 V29）：
--   原实现的版本令牌 = update_time 的 epoch 毫秒，CAS 为 WHERE update_time = ?。
--   所有写入方都用 java.util.Date（整毫秒），等值比较是精确的，但令牌**不保证前进**：
--     P 读令牌 T → A 在同一毫秒提交（update_time 仍 = T，令牌没前进）
--       → B 读 T 提交（仍命中）→ P 用修改前读到的 T 提交（也命中）
--       ⇒ P 静默覆盖 B，双方都收到成功（lost update）。
--   次生风险：令牌源是 JVM 时钟，NTP 回拨或多实例时钟偏移会让令牌不前进甚至回退。
--   这与 D10 给会话改名加 bigint version 要消除的缺陷同类，故同样用行内计数器替换。
--
-- 形状：API 不变（KnowledgeDocumentVO.version / expectedVersion 仍是 Long，前端零改动），
--       只把**数值含义**从"毫秒时间戳"改成"从 0 起的计数器"。
--
-- 幂等：本脚本可重复执行（ADD COLUMN IF NOT EXISTS + 回填 + SET DEFAULT/NOT NULL +
--       约束先 DROP IF EXISTS 再 ADD + 后置校验），重复跑不报错、不改变结果。
-- 边界：不改任何冻结迁移（V7/V9/V10/V11 只读）；只动 platform.ai_knowledge_document 一张表；
--       不新增菜单/权限行（本卡无权限面变更）。
-- 表名以冻结 V7__unified_ai_domain.sql 的实际定义为准：platform.ai_knowledge_document。
-- 真库执行由 T8/RW-26 在专属窗口验证（本卡记 NOT_RUN）。

-- 1) 加列（幂等）。刻意用 information_schema 的存在性判断，而不是 ADD COLUMN IF NOT EXISTS：
--    P1MergedAppendColumnRegistryGuardTest 把**任何文件**里对 6 张合并表的
--    `ADD COLUMN IF NOT EXISTS` 都当作"必须登记的追加列"，而它的登记表
--    （src/test/resources/unified/merged-append-column-registry.json）被明确限定为
--    **冻结 V9** 的口径：counts 固定 68/62/6，且断言 registered == V9 语句集合。
--    新迁移在那份登记表里没有位置（改它等于改冻结口径，且该文件不在本卡租约内）。
--    本卡不越界、也不放宽护栏，因此用等价的显式判断实现幂等——
--    语义比 ADD COLUMN IF NOT EXISTS 更强：列已存在但可空/无默认时同样收敛（见 2) 3)）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'platform'
           AND table_name = 'ai_knowledge_document'
           AND column_name = 'version'
    ) THEN
        ALTER TABLE platform.ai_knowledge_document ADD COLUMN version bigint;
    END IF;
END $$;

-- 2) 既有行回填 0（新列刚加出来时既有行为 NULL；重复执行时这条件不命中任何行）
UPDATE platform.ai_knowledge_document
   SET version = 0
 WHERE version IS NULL;

-- 3) 默认值与 NOT NULL 收敛（重跑安全）
ALTER TABLE platform.ai_knowledge_document
    ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE platform.ai_knowledge_document
    ALTER COLUMN version SET NOT NULL;

-- 4) 计数器不得为负（先删后加，保证幂等）
ALTER TABLE platform.ai_knowledge_document
    DROP CONSTRAINT IF EXISTS ck_knowledge_document_version_nonneg;
ALTER TABLE platform.ai_knowledge_document
    ADD CONSTRAINT ck_knowledge_document_version_nonneg CHECK (version >= 0);

COMMENT ON COLUMN platform.ai_knowledge_document.version IS
    '乐观锁版本计数器（0 起，每次成功写入 +1）；取代 update_time 毫秒令牌';

-- 5) 后置校验（与 V28 同风格）：形状不对就整体失败，不留下"看起来迁移过了"的库
DO $$
DECLARE
    v_type      text;
    v_nullable  text;
    v_default   text;
    v_nulls     bigint;
BEGIN
    SELECT data_type, is_nullable, column_default
      INTO v_type, v_nullable, v_default
      FROM information_schema.columns
     WHERE table_schema = 'platform'
       AND table_name = 'ai_knowledge_document'
       AND column_name = 'version';

    IF v_type IS NULL THEN
        RAISE EXCEPTION 'V29: platform.ai_knowledge_document.version 未建立';
    END IF;
    IF v_type <> 'bigint' THEN
        RAISE EXCEPTION 'V29: version 类型应为 bigint，实际为 %', v_type;
    END IF;
    IF v_nullable <> 'NO' THEN
        RAISE EXCEPTION 'V29: version 必须 NOT NULL，实际 is_nullable=%', v_nullable;
    END IF;
    IF v_default IS NULL OR btrim(v_default) NOT LIKE '0%' THEN
        RAISE EXCEPTION 'V29: version 默认值应为 0，实际为 %', coalesce(v_default, '<null>');
    END IF;

    EXECUTE 'SELECT count(*) FROM platform.ai_knowledge_document WHERE version IS NULL'
       INTO v_nulls;
    IF v_nulls <> 0 THEN
        RAISE EXCEPTION 'V29: 仍有 % 行 version 为 NULL', v_nulls;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_knowledge_document_version_nonneg'
           AND conrelid = 'platform.ai_knowledge_document'::regclass
    ) THEN
        RAISE EXCEPTION 'V29: 缺 ck_knowledge_document_version_nonneg 约束';
    END IF;
END $$;
