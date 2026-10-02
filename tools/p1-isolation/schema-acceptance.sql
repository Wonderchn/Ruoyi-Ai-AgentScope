-- P1 活 schema 逐表签收（合成库实际应用完整迁移后执行）。
--
-- 输出机器可读行（| 分隔），与 services/ai/rag/src/test/resources/p1/table-attribution.json
-- 逐表比对。三类行：
--   PK|<table>|<pk columns 逗号分隔，按键序>
--   NN|<table>|<column>                     （tenant_id / member_id / owner_member_id 的 NOT NULL）
--   UQ|<table>|<constraint name>|<columns>  （唯一约束与唯一索引，部分索引带 WHERE 前缀 W|）
--   FK|<table>|<child columns>|<ref table>|<ref columns>
--   CK|<table>|<constraint name>            （引用了 tenant_id 的 CHECK）
--
-- 本脚本只读 information_schema/pg_catalog，不写任何数据。

SET search_path TO ai,extensions;

SELECT 'PK|' || tc.table_name || '|' ||
       COALESCE((SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)
                 FROM information_schema.key_column_usage kcu
                 WHERE kcu.constraint_name = tc.constraint_name
                   AND kcu.table_schema = tc.table_schema), '')
FROM information_schema.table_constraints tc
WHERE tc.table_schema = 'ai' AND tc.constraint_type = 'PRIMARY KEY'
ORDER BY tc.table_name;

SELECT 'NN|' || c.table_name || '|' || c.column_name
FROM information_schema.columns c
WHERE c.table_schema = 'ai'
  AND c.column_name IN ('tenant_id', 'member_id', 'owner_member_id')
  AND c.is_nullable = 'NO'
ORDER BY c.table_name, c.column_name;

SELECT 'UQ|' || tc.table_name || '|' || tc.constraint_name || '|' ||
       (SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)
        FROM information_schema.key_column_usage kcu
        WHERE kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema)
FROM information_schema.table_constraints tc
WHERE tc.table_schema = 'ai' AND tc.constraint_type = 'UNIQUE'
ORDER BY tc.table_name, tc.constraint_name;

SELECT 'UQ|' || t.relname || '|' || i.relname || '|W|' ||
       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
        FROM unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum)
FROM pg_index ix
JOIN pg_class i ON i.oid = ix.indexrelid
JOIN pg_class t ON t.oid = ix.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'ai' AND ix.indisunique AND NOT ix.indisprimary
  AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints tc
                  WHERE tc.table_schema = 'ai' AND tc.table_name = t.relname
                    AND tc.constraint_name = i.relname)
ORDER BY t.relname, i.relname;

SELECT 'FK|' || child.relname || '|' ||
       (SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num)
       || '|' || parent.relname || '|' ||
       (SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM unnest(c.confkey) WITH ORDINALITY k(num,ord)
        JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num)
FROM pg_constraint c
JOIN pg_class child ON child.oid=c.conrelid
JOIN pg_class parent ON parent.oid=c.confrelid
WHERE c.connamespace='ai'::regnamespace AND c.contype='f'
ORDER BY child.relname,c.conname;

SELECT 'CK|' || conrelid::regclass::text || '|' || conname
FROM pg_constraint
WHERE connamespace = 'ai'::regnamespace AND contype = 'c'
  AND pg_get_constraintdef(oid) LIKE '%tenant_id%'
ORDER BY conrelid::regclass::text, conname;
