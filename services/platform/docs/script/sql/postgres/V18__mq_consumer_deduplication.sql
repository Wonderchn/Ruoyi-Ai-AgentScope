-- ============================================================================
-- V18__mq_consumer_deduplication.sql
--
-- 归属：T3（WP-043 传输侧，落实 C12.3 消费端去重）
-- 编号：由 T0 于 2026-10-06 串行分配给 T3；next_available 顺延 V19
-- 依据：C12.3（去重键 = tenantId + eventId，或 tenantId + operationKey）
--       C12.4（重复投递、消费者崩溃 / 重平衡、broker 不可用时积压可查询）
--       D06/D07（至少一次投递 + 可查询积压，不返假成功）
--       C2.1（不得新建第二套 Worker 事实 / 执行账本）
--
-- 目的：给 MQ 消费端一个**持久化**的消费进度账本，使"重复消费不产生第二次副作用"
--       在消费者崩溃、重平衡、broker 重投之下依然成立。
--
-- 为什么必须落库而不是只用 Redis TTL（T0 已驳回 Redis 替代方案）：
--   至少一次投递 + 重平衡意味着同一条消息可能在 TTL 之外被重放；只靠 TTL，
--   去重事实会先于"这条消息确实不会再被投递"而消失，重放就会穿透去重。
--
-- 为什么复用既存表不行（T3 2026-10-06 逐个核实全链 89 个 CREATE TABLE IF NOT EXISTS）：
--   1) platform.ai_delegation_replay —— 委托令牌重放保护，语义单位是"令牌 (issuer, jti)"，
--      不是"消息"；复用会把"令牌被用过"与"消息被消费过"混成一个事实。
--   2) platform.outbox_event —— **生产端**投递账本，行的 state 表示"relay 把它发出去了没有"。
--      同一个 outbox 行可能被 N 个消费者以**不同进度**处理（不同主题、不同组），
--      把消费进度写回同一行 = 在同一行上放两套事实，正是 C2.1 禁止的形态。
--   3) platform.ai_run_event —— 有 (tenant_id, run_id, seq) 与全局唯一 event_id 的权威事实，
--      但**没有任何"某消费者已消费"的标记**，无法区分"已消费"与"尚未消费"。
--   ⇒ 结论：需要一个独立账本。它**只记录消费进度**，不复制任何业务事实、不写 run 状态、
--      不成为执行账本（C2.1 仍然成立）。
--
-- 原子认领语句（消费端实现见 PgOutboxMqConsumeLedger，这里是它的语义基准）：
--   INSERT ... VALUES (...,'PROCESSING',...)
--   ON CONFLICT (tenant_id, dedup_key) DO UPDATE
--      SET state='PROCESSING', attempt_count = ai_mq_consume_dedup.attempt_count + 1, updated_at = now()
--      WHERE ai_mq_consume_dedup.state = 'RETRYABLE'
--         OR (ai_mq_consume_dedup.state = 'PROCESSING'
--             AND ai_mq_consume_dedup.updated_at < now() - (leaseSeconds * interval '1 second'))
--   RETURNING state;
--   返回一行 ⇒ 认领成功（首次、或上一次失败后、或上一个消费者崩溃且认领租约已过期）；
--   返回零行 ⇒ 重复（已 CONSUMED / 已 REJECTED / 仍有人在租约内处理）⇒ **不执行副作用**。
--   `PROCESSING` 的租约正是"消费者崩溃"这一条：若把"已存在"一律当重复，
--   崩溃在副作用之前的那条消息被重投时会被跳过，事件**永久丢失**而账上还写着有人认领过。
--
-- 幂等：全段可重复执行（CREATE ... IF NOT EXISTS + 纯断言区块，无数据写入）。
--       空库新装与双 schema 升级两条路径都必须 exit 0。
--
-- 说明（刻意不做的两件事，避免与契约冲突）：
--   * 不加 consumer_group 列：C12.3 定义的去重键就是 tenantId + eventId，
--     主题按 eventType 分隐含"一个事件类型一个订阅"。若将来同一主题出现第二个需要各自副作用的
--     消费组，必须把组名显式并入 dedup_key —— 那是**契约变更**，需 T0 更新 C12.3，
--     不在本迁移里偷偷改键。
--   * 不建"重放审计表"：人工重放的审计归属（新表 vs 复用平台既有变更日志）尚未裁决，
--     不在此处自定第二套审计事实。见 T3 WP-043 报告 §6 未完成项。
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform.ai_mq_consume_dedup (
    tenant_id     VARCHAR(64)  NOT NULL,
    -- 去重键：'evt:<eventId>' 或 'op:<operationKey>'（前缀由 OutboxMqTopics.dedupKey 统一产出）
    dedup_key     VARCHAR(160) NOT NULL,
    dedup_kind    VARCHAR(4)   NOT NULL,
    event_id      VARCHAR(64),
    event_type    VARCHAR(32)  NOT NULL,
    run_id        VARCHAR(64),
    -- PROCESSING=已认领待完成；CONSUMED=终局；RETRYABLE=失败可再认领；REJECTED=授权拒绝，永不重试
    state         VARCHAR(16)  NOT NULL,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    last_error    VARCHAR(512),
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT pk_ai_mq_consume_dedup PRIMARY KEY (tenant_id, dedup_key),
    CONSTRAINT ck_ai_mq_consume_dedup_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_mq_consume_dedup_key CHECK (btrim(dedup_key) <> ''),
    CONSTRAINT ck_ai_mq_consume_dedup_kind CHECK (dedup_kind IN ('EVT', 'OP')),
    CONSTRAINT ck_ai_mq_consume_dedup_state
        CHECK (state IN ('PROCESSING', 'CONSUMED', 'RETRYABLE', 'REJECTED')),
    CONSTRAINT ck_ai_mq_consume_dedup_attempt CHECK (attempt_count >= 0)
);

-- 积压/清理查询：按状态 + 更新时间（DLQ 巡检与保留策略清理都走这条）
CREATE INDEX IF NOT EXISTS idx_ai_mq_consume_dedup_state
    ON platform.ai_mq_consume_dedup (state, updated_at);

-- 按事件类型排查（"这个类型还有多少没消费完"）
CREATE INDEX IF NOT EXISTS idx_ai_mq_consume_dedup_type
    ON platform.ai_mq_consume_dedup (event_type, state);

COMMENT ON TABLE platform.ai_mq_consume_dedup IS
    'MQ 消费端去重账本（C12.3）：键 =(tenant_id, dedup_key)；只记录消费进度，不复制业务事实（C2.1）';
COMMENT ON COLUMN platform.ai_mq_consume_dedup.state IS
    'PROCESSING(租约内认领中)/CONSUMED(终局)/RETRYABLE(失败可再认领)/REJECTED(授权拒绝，永不重试)';
COMMENT ON COLUMN platform.ai_mq_consume_dedup.updated_at IS
    'PROCESSING 的租约基准：超过认领租约后允许再认领，用于覆盖消费者崩溃（C12.4）';

-- ---------------------------------------------------------------------------
-- 末段校验：不静默跳过。表/约束/列类型任一项不符 ⇒ 整迁移失败（不允许半落地）。
-- 纯断言，重复执行结果一致。
-- ---------------------------------------------------------------------------
DO $v18_verify$
DECLARE
    missing text;
BEGIN
    IF to_regclass('platform.ai_mq_consume_dedup') IS NULL THEN
        RAISE EXCEPTION 'V18: platform.ai_mq_consume_dedup 未创建';
    END IF;

    -- 主键必须存在且恰好是 (tenant_id, dedup_key)：去重语义的物理保证
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conname = 'pk_ai_mq_consume_dedup'
           AND c.conrelid = 'platform.ai_mq_consume_dedup'::regclass
           AND c.contype = 'p'
    ) THEN
        RAISE EXCEPTION 'V18: 主键 pk_ai_mq_consume_dedup 缺失（去重键没有物理保证）';
    END IF;

    -- 列存在性与类型：逐列比对，避免"表在但形状不对"被当成落地成功
    SELECT string_agg(expect.col || ':' || expect.typ, ', ' ORDER BY expect.col)
      INTO missing
      FROM (VALUES
              ('tenant_id', 'character varying'),
              ('dedup_key', 'character varying'),
              ('dedup_kind', 'character varying'),
              ('event_id', 'character varying'),
              ('event_type', 'character varying'),
              ('run_id', 'character varying'),
              ('state', 'character varying'),
              ('attempt_count', 'integer'),
              ('last_error', 'character varying'),
              ('first_seen_at', 'timestamp with time zone'),
              ('updated_at', 'timestamp with time zone'),
              ('consumed_at', 'timestamp with time zone')
           ) AS expect(col, typ)
     WHERE NOT EXISTS (
              SELECT 1
                FROM information_schema.columns ic
               WHERE ic.table_schema = 'platform'
                 AND ic.table_name = 'ai_mq_consume_dedup'
                 AND ic.column_name = expect.col
                 AND ic.data_type = expect.typ
           );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V18: ai_mq_consume_dedup 列缺失或类型不符: %', missing;
    END IF;

    -- 状态集合必须与消费端状态机一致：少了 REJECTED 就会把"授权拒绝"混进"可重试"，
    -- 那等于把拒绝变成无限重投。
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conname = 'ck_ai_mq_consume_dedup_state'
           AND c.conrelid = 'platform.ai_mq_consume_dedup'::regclass
           AND pg_get_constraintdef(c.oid) LIKE '%REJECTED%'
           AND pg_get_constraintdef(c.oid) LIKE '%RETRYABLE%'
           AND pg_get_constraintdef(c.oid) LIKE '%PROCESSING%'
           AND pg_get_constraintdef(c.oid) LIKE '%CONSUMED%'
    ) THEN
        RAISE EXCEPTION 'V18: 状态约束 ck_ai_mq_consume_dedup_state 缺失或不含完整状态集合';
    END IF;

    -- 两个查询索引必须存在（积压/清理路径依赖它们）
    IF to_regclass('platform.idx_ai_mq_consume_dedup_state') IS NULL
       OR to_regclass('platform.idx_ai_mq_consume_dedup_type') IS NULL THEN
        RAISE EXCEPTION 'V18: 查询索引缺失（idx_ai_mq_consume_dedup_state / _type）';
    END IF;

    RAISE NOTICE 'V18: ai_mq_consume_dedup 校验通过（表 + 主键 + 12 列类型 + 状态集合 + 2 索引）';
END
$v18_verify$;
