-- V6：AI 侧租户屏障状态（U10 强撤权协议）。
--
-- 与 platform V4 的 sys_ai_tenant_barrier 对应：platform 是政策变更的发起方，
-- AI 侧保存本节点可见的租户屏障状态，供 (a) /internal/ai/v1/authorization/barriers
-- 的 service 身份查询/关闭；(b) 高风险操作在登记 permit 前检查屏障。
--
-- 状态语义（与 05 §4.3 一致）：
--   OPEN     正常服务新 permit；
--   PENDING  drain 中：拒绝新 permit，等待活跃段退出；
--   CLOSED   撤权完成/受控关闭：拒绝新 permit；
--   UNKNOWN  节点失联或状态不可判定：保持关闭并要求处置，**不得凭租约过期推断成功**。
--
-- 不预置行：无行 = 从未设防（OPEN 语义由应用层显式判断，缺行不默认放行新 permit
-- 的路径必须由调用方写明；本表只为"已设防"提供事实）。

CREATE TABLE ai_tenant_barrier (
    tenant_id          VARCHAR(64) NOT NULL PRIMARY KEY,
    status             VARCHAR(16) NOT NULL,
    barrier_id         VARCHAR(64),
    target_acl_version INTEGER,
    reason             VARCHAR(255),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_tenant_barrier_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_ai_tenant_barrier_status CHECK (status IN ('OPEN', 'PENDING', 'CLOSED', 'UNKNOWN'))
);
COMMENT ON TABLE ai_tenant_barrier IS 'AI侧租户屏障：PENDING/CLOSED/UNKNOWN 拒绝新permit；UNKNOWN保持关闭直至可证实安全';
