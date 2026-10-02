-- Deliberately shared selectors and private sibling resources, backed by real product authorization.
INSERT INTO ai.t_knowledge_base(id,name,embedding_model,collection_name,created_by,tenant_id,owner_member_id,owner_dept_id,deleted) VALUES
('kb-t1-private-a','synthetic-t1','synthetic','shared','900000000000000001','p1t1','platform:p1t1:900000000000000001','5101',0),
('kb-t2-same-selector','synthetic-t2','synthetic','shared','900000000000000002','p1t2','platform:p1t2:900000000000000002','5201',0),
('kb-t1-private-b','private sibling','synthetic','sibling','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0),
('kb-t1-shared-src','source grant','synthetic','source','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0),
('kb-t1-tenant-all','explicit tenant all','synthetic','all','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0),
('kb-t1-dept','department grant','synthetic','dept','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0),
('kb-t1-role','role grant','synthetic','role','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0),
('kb-t1-expired','expired grant','synthetic','expired','900000000000000003','p1t1','platform:p1t1:900000000000000003','5102',0);
INSERT INTO ai.ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,status,resource_version)
SELECT tenant_id,'KB',id,owner_member_id,owner_dept_id,'ACTIVE',1 FROM ai.t_knowledge_base;
INSERT INTO ai.ai_resource_acl(id,tenant_id,resource_type,resource_id,subject_type,subject_id,action,granted_by) VALUES
('c-t1','p1t1','KB','kb-t1-private-a','MEMBER','platform:p1t1:900000000000000001','kb.read','platform:p1t1:900000000000000001'),
('c-t2','p1t2','KB','kb-t2-same-selector','MEMBER','platform:p1t2:900000000000000002','kb.read','platform:p1t2:900000000000000002'),
('c-source','p1t1','KB','kb-t1-shared-src','MEMBER','platform:p1t1:900000000000000001','kb.read','platform:p1t1:900000000000000003'),
('c-manage','p1t1','KB','kb-t1-shared-src','MEMBER','platform:p1t1:900000000000000001','kb.acl.manage','platform:p1t1:900000000000000003'),
('c-all','p1t1','KB','kb-t1-tenant-all','TENANT_ALL',NULL,'kb.read','platform:p1t1:900000000000000003'),
('c-dept','p1t1','KB','kb-t1-dept','DEPT','5101','kb.read','platform:p1t1:900000000000000003'),
('c-role','p1t1','KB','kb-t1-role','ROLE','900000000000000021','kb.read','platform:p1t1:900000000000000003');
INSERT INTO ai.ai_resource_acl(id,tenant_id,resource_type,resource_id,subject_type,subject_id,action,expires_at,granted_by)
VALUES('c-expired','p1t1','KB','kb-t1-expired','MEMBER','platform:p1t1:900000000000000001','kb.read',now()-interval '1 day','platform:p1t1:900000000000000003');
INSERT INTO ai.t_knowledge_document(id,kb_id,doc_name,file_url,file_type,mime_type,status,created_by,tenant_id,deleted) VALUES
('doc-t1-a','kb-t1-private-a','synthetic bytes','p1t1/shared/11111111-1111-4111-8111-111111111111.txt','txt','text/plain','completed','900000000000000001','p1t1',0),
('doc-t1-b','kb-t1-private-b','private sibling','p1t1/shared/22222222-2222-4222-8222-222222222222.txt','txt','text/plain','completed','900000000000000003','p1t1',0),
('doc-t2-a','kb-t2-same-selector','cross tenant','p1t2/shared/33333333-3333-4333-8333-333333333333.txt','txt','text/plain','completed','900000000000000002','p1t2',0);
INSERT INTO ai.ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,parent_type,parent_id,status,resource_version)
SELECT d.tenant_id,'DOCUMENT',d.id,k.owner_member_id,k.owner_dept_id,'KB',d.kb_id,'ACTIVE',1 FROM ai.t_knowledge_document d JOIN ai.t_knowledge_base k ON k.tenant_id=d.tenant_id AND k.id=d.kb_id;
INSERT INTO ai.ai_resource(tenant_id,resource_type,resource_id,owner_member_id,parent_type,parent_id,status,resource_version)
SELECT d.tenant_id,'OBJECT',split_part(split_part(d.file_url,'/',3),'.',1),k.owner_member_id,'DOCUMENT',d.id,'ACTIVE',1 FROM ai.t_knowledge_document d JOIN ai.t_knowledge_base k ON k.tenant_id=d.tenant_id AND k.id=d.kb_id;
INSERT INTO ai.t_knowledge_vector(id,tenant_id,collection_name,content,embedding,document_id,doc_version,deleted)
SELECT v.id,d.tenant_id,'shared',v.content,('['||'1,'||repeat('0,',1534)||'0]')::extensions.vector,d.id,v.version,0
FROM (VALUES('chunk-t1-a','doc-t1-a','allowed content',1),('chunk-t1-b','doc-t1-b','PRIVATE sibling content',1),
('chunk-t2-a','doc-t2-a','PRIVATE T2 content',1),('chunk-t1-old','doc-t1-a','PRIVATE obsolete version',2)) v(id,doc,content,version)
JOIN ai.t_knowledge_document d ON d.id=v.doc;
INSERT INTO ai.t_conversation(id,conversation_id,user_id,title,last_time,tenant_id,member_id,deleted) VALUES
('c-t1-a','conv-t1-a','900000000000000001','T1 private conversation',now(),'p1t1','platform:p1t1:900000000000000001',0),
('c-t1-b','conv-t1-b','900000000000000003','T1 other member',now(),'p1t1','platform:p1t1:900000000000000003',0),
('c-t2-a','conv-t2-a','900000000000000002','T2 private conversation',now(),'p1t2','platform:p1t2:900000000000000002',0);
INSERT INTO ai.ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,status,resource_version)
SELECT c.tenant_id,'CONVERSATION',c.conversation_id,c.member_id,k.owner_dept_id,'ACTIVE',1 FROM ai.t_conversation c JOIN ai.t_knowledge_base k ON k.owner_member_id=c.member_id AND k.id IN ('kb-t1-private-a','kb-t1-private-b','kb-t2-same-selector');
INSERT INTO ai.t_message(id,conversation_id,user_id,role,content,tenant_id,member_id,deleted)
SELECT 'msg-'||id,conversation_id,user_id,'user',title,tenant_id,member_id,0 FROM ai.t_conversation;
INSERT INTO ai.ai_run(tenant_id,run_id,member_id,action,status,policy_version,acl_version,resource_refs) VALUES
('p1t1','run-t1-a','platform:p1t1:900000000000000001','kb.read','COMPLETED',1,1,'[{"ref":"kb:kb-t1-private-a","version":1}]'),
('p1t2','run-t2-a','platform:p1t2:900000000000000002','kb.read','COMPLETED',1,1,'[{"ref":"kb:kb-t2-same-selector","version":1}]');
INSERT INTO ai.ai_resource(tenant_id,resource_type,resource_id,owner_member_id,owner_dept_id,status,resource_version) VALUES
('p1t1','RUN','run-t1-a','platform:p1t1:900000000000000001','5101','ACTIVE',1),('p1t2','RUN','run-t2-a','platform:p1t2:900000000000000002','5201','ACTIVE',1);
INSERT INTO ai.ai_run_event(tenant_id,run_id,seq,event_type,payload) VALUES
('p1t1','run-t1-a',1,'RESULT','{"content":"allowed result"}'),('p1t1','run-t1-a',2,'DONE','{}'),('p1t2','run-t2-a',1,'RESULT','{"content":"PRIVATE T2 result"}');
