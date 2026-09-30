-- platform 库 bootstrap（必须由超级账号或类型 owner 执行，不进 Flyway）
-- 依据实测：1) CREATE EXTENSION vector 需超级账号；2) 在 varchar/timestamptz 两个内置类型之间
--            CREATE CAST 报 "must be owner of type character varying or type timestamp with time zone"。
-- 这两类对象都是 database 级，不属于任一业务域，因此与迁移历史分离、单独成脚本。
-- 若依框架的字符串时间条件依赖该隐式 cast，缺失会在运行期表现为时间查询报错，不是可选项。

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;

-- 字符串自动转时间 避免框架时间查询报错问题
create or replace function cast_varchar_to_timestamp(varchar) returns timestamptz as $$
select to_timestamp($1, 'yyyy-mm-dd hh24:mi:ss');
$$ language sql strict ;

create cast (varchar as timestamptz) with function cast_varchar_to_timestamp as IMPLICIT;
