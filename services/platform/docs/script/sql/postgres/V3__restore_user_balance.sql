-- V3：补回 sys_user.user_balance。
--
-- 背景：V1 依据“不沿用旧余额账务”的裁决未迁该列。DB2 步骤 8 的真实启动验证证明
-- 应用侧仍在登录路径上读它：SysUser 实体经 MyBatis-Plus selectVoOne 生成列清单，
-- 以及 SysUserMapper.xml 的 selectUserByOpenId。缺列直接导致 /auth/login 返回 500，
-- PostgreSQL 侧的确切报错为：
--   ERROR:     column "user_balance" does not exist
--   STATEMENT: SELECT user_id, open_id, user_balance, dept_id, ... FROM sys_user
--              WHERE del_flag = '0' AND (user_name = $1) AND tenant_id = '000000'
--
-- DB2 的验收标准是“功能等价”，而 MySQL 侧该列存在且被读写，故补回；
-- 余额账务模型是否保留、是否改为额度表属产品决策，不在本迁移范围内。
--
-- 定义逐字对照本地 MySQL docs/script/sql/ruoyi-ai.sql:3383：
--   `user_balance` double(20, 2) NULL DEFAULT 0.00 COMMENT '账户余额'
-- double(20,2) -> numeric(20,2)：与 flow_node.node_ratio（numeric(6,3)）采用同一映射口径。
-- MySQL 中该列位于 open_id 之后、表末，ADD COLUMN 追加到末尾，列序一致。

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS user_balance numeric(20, 2) NULL DEFAULT 0.00;

COMMENT ON COLUMN sys_user.user_balance IS '账户余额';
