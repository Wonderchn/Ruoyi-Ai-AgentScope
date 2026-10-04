-- V5：P2 正式运行/文档动作的 AI 菜单权限（保留段 7100–7199 续用；只声明，不分配）。
--
-- 与 AI 侧 V8 增量迁移对应：新增 run 受理/取消/恢复/流与文档上传/摄入能力。
-- 权限值即平台动作表中的 canonical permission；菜单类型 F（按钮）挂在 V4 建立的
-- 隐藏目录 7100 下。**不默认分配给任何租户/角色**——授权由验收 fixture/运维显式赋予。
-- 编号 7114–7121 与 V4 的 7100–7113 不重叠；冲突由主键直接暴露，不静默跳过。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7114, 'run受理', 7100, 14, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:submit', '#', 103, 1, now(), NULL, NULL, 'P2 正式run提交（rag.chat/document.ingest）'),
(7115, 'run取消', 7100, 15, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:cancel', '#', 103, 1, now(), NULL, NULL, ''),
(7116, 'run恢复', 7100, 16, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:resume', '#', 103, 1, now(), NULL, NULL, ''),
(7117, 'run事件流', 7100, 17, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:run:stream', '#', 103, 1, now(), NULL, NULL, 'P2 专用SSE传输'),
(7118, '文档上传', 7100, 18, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:document:upload', '#', 103, 1, now(), NULL, NULL, 'P2 专用流式上传传输'),
(7119, '文档摄入', 7100, 19, '#', NULL, '', 1, 0, 'F', '1', '0', 'ai:document:ingest', '#', 103, 1, now(), NULL, NULL, '');
