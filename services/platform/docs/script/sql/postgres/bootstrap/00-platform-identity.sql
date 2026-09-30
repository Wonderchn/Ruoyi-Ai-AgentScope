-- platform 域身份与 schema bootstrap（必须由超级账号执行；**不进 Flyway**）
--
-- 为什么这份文件要进仓库：等价物一直只存在于测试 VM 的 /opt/ruoyi-pg/scripts/initdb/ 下，
-- 仓库里没有对应件 ⇒ CI 与真实部署会各写一份并漂移。DB2 步骤 9 实测出两条必须原样保留的事实：
--   1) `migrate_platform` 在业务库**没有** database 级 CREATE（实测 `permission denied for database`），
--      所以域 schema 只能由超级账号预建，Flyway 只能在域内建对象；
--   2) `REVOKE ALL ON SCHEMA public FROM PUBLIC` 是"应用连错域也读不到 public"的唯一防线
--      ——步骤 9 实测应用层对此毫无识别能力（public 里有同名可读对象时照常返回那份数据）。
--
-- 口令：用 \getenv 从环境变量进入 psql 变量，仓库内不出现任何字面口令，也不经命令行参数
--      （CI 侧以 docker -e 变量名传入，不写口令文件）。
-- 执行顺序：**先 bootstrap/01-platform-casts.sql，再本文件**。01 创建 extensions（含 vector），
--      本文件对 extensions 的 USAGE 授予需要该 schema 已存在。
-- 幂等：角色用 \gset + \if 判存在；schema/授权本身可重复执行。已存在但 owner 不同的 schema
--      不会被 IF NOT EXISTS 纠正，属有意保留（自动改 owner 风险更大），由断言脚本单独校验。

\set ON_ERROR_STOP on
\getenv migrate_password PLATFORM_MIGRATE_PASSWORD
\getenv app_password PLATFORM_APP_PASSWORD

-- 守卫：\getenv 在变量缺失时得到空串，而 `CREATE ROLE ... PASSWORD ''` 是合法的（等于不设口令）。
-- 用一次除零，让它在建出无口令角色之前硬性失败。
SELECT 1 / (CASE WHEN length(:'migrate_password') >= 12
                  AND length(:'app_password')     >= 12 THEN 1 ELSE 0 END) AS password_length_guard;

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migrate_platform') AS create_migrate \gset
\if :create_migrate
CREATE ROLE migrate_platform LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migrate_password';
\else
ALTER ROLE migrate_platform PASSWORD :'migrate_password';
\endif

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') AS create_app \gset
\if :create_app
CREATE ROLE platform_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'app_password';
\else
ALTER ROLE platform_app PASSWORD :'app_password';
\endif

CREATE SCHEMA IF NOT EXISTS platform AUTHORIZATION migrate_platform;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM migrate_platform, platform_app;

GRANT USAGE ON SCHEMA platform TO platform_app;

ALTER ROLE migrate_platform SET search_path = platform, extensions;
ALTER ROLE platform_app     SET search_path = platform, extensions;

ALTER DEFAULT PRIVILEGES FOR ROLE migrate_platform IN SCHEMA platform
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_app;
ALTER DEFAULT PRIVILEGES FOR ROLE migrate_platform IN SCHEMA platform
  GRANT USAGE, SELECT ON SEQUENCES TO platform_app;

SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'extensions') AS has_extensions \gset
\if :has_extensions
GRANT USAGE ON SCHEMA extensions TO migrate_platform, platform_app;
\else
\echo 'NOTE: extensions schema 尚不存在；按 01 -> 00 的顺序执行，或重跑本文件以补 USAGE 授予'
\endif

SELECT 'platform identity bootstrap applied' AS status,
       (SELECT count(*) FROM pg_roles WHERE rolname IN ('migrate_platform', 'platform_app')) AS roles,
       (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'platform') AS platform_owner;
