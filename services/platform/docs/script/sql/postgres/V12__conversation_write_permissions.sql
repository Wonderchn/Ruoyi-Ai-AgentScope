-- WP-034A：F03 会话写入（重命名 / 删除）的权限声明。
--
-- 与 V4/V5/V6 同一约定：只声明权限行（menu_type='F'，挂在 7100「AI」下），
-- **不**给任何角色默认分配——授予由部署方按最小权限决定。
--
-- 这两行与 ruoyi-ai-api 的 AiCanonicalAction 表一一对应：
--   conversation.rename -> ai:conversation:write
--   conversation.delete -> ai:conversation:delete
-- 双向一致性由 P1AiPermissionContractTest 钉住（规范动作 ↔ 权限行 ↔ 网关路由）。
--
-- 注意：本迁移只注册权限，**不**开放网关路由。会话写入端点的放行要等
-- ConversationController 及其依赖链（ConversationService/ConversationMessageService/
-- ConversationTitleGenerator/MessageFeedbackService 等）装配完成并通过验证之后，
-- 否则会得到一个"权限可授予但端点 404"的假公开面。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(7124,'会话重命名',7100,24,'#',NULL,'',1,0,'F','1','0','ai:conversation:write','#',103,1,now(),NULL,NULL,'WP-034A 会话写入；默认不分配，路由待 ConversationController 装配后放行'),
(7125,'会话删除',7100,25,'#',NULL,'',1,0,'F','1','0','ai:conversation:delete','#',103,1,now(),NULL,NULL,'WP-034A 会话写入；默认不分配，路由待 ConversationController 装配后放行');
