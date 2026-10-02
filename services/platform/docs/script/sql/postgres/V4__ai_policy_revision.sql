-- V4：AI 授权政策版本、执行 permit 与租户屏障（platform 侧）。
--
-- 与 AI 侧 V3/V4/V5 对应（05 §4.3）：platform 持 policyVersion 权威，
-- AI 持 aclVersion；双方通过 /internal/platform/v1 两个端点交换判定。
--
-- 不预置任何租户的 policy revision 行：无版本/读失败一律拒绝，
-- 新租户在创建事务内同建 revision 行，旧租户初始化来源经 C6 确认——
-- 本迁移不得替 C6 做"默认初始化到 1"的决定。
--
-- 菜单种子使用保留固定段 7100–7199（V1/V2 的 menu_id 小值止于 3006，
-- 大值为雪花段 2xxxxx，无碰撞；固定段内冲突由主键直接暴露，不静默跳过）。
-- 菜单只声明权限值，不默认分配给任何租户/角色——授权由 fixture/运维显式赋予。

-- ============================================
-- 1. 政策版本（policyVersion 权威）
-- ============================================

CREATE TABLE sys_ai_policy_revision (
    tenant_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    version     INTEGER     NOT NULL,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sys_ai_policy_revision_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_sys_ai_policy_revision_version CHECK (version >= 1)
);
COMMENT ON TABLE sys_ai_policy_revision IS 'AI政策版本：租户一行；政策写事务内锁定并同事务递增，不预置行';

-- ============================================
-- 2. 执行 permit 与租户屏障状态
-- ============================================

CREATE TABLE sys_ai_execution_permit (
    permit_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    member_id          VARCHAR(160) NOT NULL,
    action             VARCHAR(64)  NOT NULL,
    policy_version     INTEGER      NOT NULL,
    resource_refs_hash VARCHAR(64)  NOT NULL,
    operation_id       VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    acquired_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    released_at        TIMESTAMPTZ,
    CONSTRAINT ck_sys_ai_execution_permit_tenant CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT ck_sys_ai_execution_permit_status CHECK (status IN ('ACTIVE', 'RELEASED', 'REVOKED')),
    CONSTRAINT uk_sys_ai_execution_permit_operation UNIQUE (operation_id)
);
CREATE INDEX idx_sys_ai_execution_permit_tenant_status ON sys_ai_execution_permit (tenant_id, status);
COMMENT ON TABLE sys_ai_execution_permit IS '平台侧permit授予账：与AI侧ai_execution_permit共同构成跨节点活跃集';

-- 屏障状态：政策变更 drain 期间 PENDING/CLOSED，UNKNOWN 要求人工处置；
-- 撤权成功以屏障状态为准，不以租约过期推断。
CREATE TABLE sys_ai_tenant_barrier (
    tenant_id             VARCHAR(64) NOT NULL PRIMARY KEY,
    status                VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    barrier_id            VARCHAR(64),
    target_policy_version INTEGER,
    reason                VARCHAR(255),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sys_ai_tenant_barrier_status CHECK (status IN ('OPEN', 'PENDING', 'CLOSED', 'UNKNOWN'))
);
COMMENT ON TABLE sys_ai_tenant_barrier IS '租户撤权屏障：drain闭合前拒绝新permit；UNKNOWN保持关闭直至可证实安全';

-- ============================================
-- 3. AI 动作菜单（保留段 7100–7199；只声明，不分配）
-- ============================================
-- 权限值即 05 §4.2 动作表中的 platform permission。菜单类型 F（按钮）挂在一个
-- 不入前端路由的目录下（visible='1' 隐藏），避免未实现页面出现在导航中。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7100, 'AI资源授权', 0, 10, 'ai-authorization', NULL, '', 1, 0, 'M', '1', '0', '', '#', 103, 1, now(), NULL, NULL, 'P1 AI canonical 动作权限目录（隐藏，仅作权限挂载点）');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7101, '知识库列表', 7100, 1, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:list', '#', 103, 1, now(), NULL, NULL, ''),
(7102, '知识库读取', 7100, 2, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:read', '#', 103, 1, now(), NULL, NULL, ''),
(7103, '知识库写入', 7100, 3, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:write', '#', 103, 1, now(), NULL, NULL, ''),
(7104, '知识库删除', 7100, 4, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:delete', '#', 103, 1, now(), NULL, NULL, ''),
(7105, '知识库ACL管理', 7100, 5, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:acl', '#', 103, 1, now(), NULL, NULL, ''),
(7106, '知识库检索', 7100, 6, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:kb:retrieve', '#', 103, 1, now(), NULL, NULL, ''),
(7107, '文档读取', 7100, 7, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:document:read', '#', 103, 1, now(), NULL, NULL, ''),
(7108, '文档下载', 7100, 8, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:document:download', '#', 103, 1, now(), NULL, NULL, ''),
(7109, '会话读取', 7100, 9, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:conversation:read', '#', 103, 1, now(), NULL, NULL, ''),
(7110, '会话导出', 7100, 10, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:conversation:export', '#', 103, 1, now(), NULL, NULL, ''),
(7111, '记忆读取', 7100, 11, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:memory:read', '#', 103, 1, now(), NULL, NULL, ''),
(7112, 'run读取', 7100, 12, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:read', '#', 103, 1, now(), NULL, NULL, ''),
(7113, 'run事件读取', 7100, 13, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:event:read', '#', 103, 1, now(), NULL, NULL, '');
