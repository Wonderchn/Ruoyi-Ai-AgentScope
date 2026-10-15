-- F04 private chat attachments. Identity comes exclusively from authenticated principal/run.
CREATE TABLE platform.ai_chat_attachment (
 tenant_id varchar(20) NOT NULL, attachment_id varchar(64) NOT NULL,
 member_id varchar(128) NOT NULL, user_id varchar(64) NOT NULL,
 filename varchar(255) NOT NULL, mime_type varchar(100) NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes > 0 AND size_bytes <= 5242880),
 sha256 char(64) NOT NULL, object_key varchar(512) NOT NULL,
 object_version bigint NOT NULL DEFAULT 1 CHECK(object_version > 0),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','TOMBSTONED')),
 create_time timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,attachment_id), UNIQUE(tenant_id,object_key)
);
CREATE TABLE platform.ai_chat_message_attachment (
 tenant_id varchar(20) NOT NULL, member_id varchar(128) NOT NULL,
 user_id varchar(64) NOT NULL, message_kind varchar(16) NOT NULL CHECK(message_kind IN ('RAG','AGENT')),
 conversation_id varchar(64) NOT NULL, message_id varchar(64) NOT NULL,
 attachment_id varchar(64) NOT NULL, object_version bigint NOT NULL,
 create_time timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,message_kind,message_id,attachment_id),
 FOREIGN KEY(tenant_id,attachment_id) REFERENCES platform.ai_chat_attachment(tenant_id,attachment_id)
);
CREATE INDEX idx_chat_message_attachment_owner ON platform.ai_chat_message_attachment(tenant_id,member_id,message_kind,conversation_id,message_id);
COMMENT ON TABLE platform.ai_chat_attachment IS 'Private chat bytes; ai_resource OBJECT parent CHAT_ATTACHMENT supplies online object status/version';
COMMENT ON TABLE platform.ai_chat_message_attachment IS 'Server-created message attachment binding, atomic with message write; no client message-id binding API';
