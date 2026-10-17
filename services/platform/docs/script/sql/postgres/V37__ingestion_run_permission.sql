-- V37__ingestion_run_permission.sql
-- F06:1/2-A（2026-10-15 维护者批）: canonical ingestion task surface.
-- 1) 权限行 ingestion.run -> ai:ingestion:run（menu_id 7143，parent 7100，order 61；仅声明不分配）。
-- 2) 任务主键 = runId（"r-"+32hex=34）⇒ 放宽 ai_ingestion_task.id / ai_ingestion_task_node.task_id。
-- 写前复核（2026-10-16 窗口实测）：7143 空；parent 7100 下最大 order_num=60；两列现宽 VARCHAR(20)。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
 (7143, '摄取任务运行', 7100, 61, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:ingestion:run', '#', 103, 1, now(), NULL, NULL, 'F06:1/2-A ingestion.run（任务提交/取消/重试）；默认不分配')
ON CONFLICT (menu_id) DO NOTHING;

ALTER TABLE ai_ingestion_task      ALTER COLUMN id      TYPE VARCHAR(64);
ALTER TABLE ai_ingestion_task_node ALTER COLUMN task_id TYPE VARCHAR(64);

DO $$
BEGIN
    IF (SELECT count(*) FROM sys_menu WHERE menu_id=7143 AND perms='ai:ingestion:run' AND menu_type='F') <> 1 THEN
        RAISE EXCEPTION 'V37 ingestion.run permission row conflict';
    END IF;
    IF (SELECT character_maximum_length FROM information_schema.columns
        WHERE table_schema='platform' AND table_name='ai_ingestion_task' AND column_name='id') <> 64 THEN
        RAISE EXCEPTION 'V37 ai_ingestion_task.id width not applied';
    END IF;
END $$;
