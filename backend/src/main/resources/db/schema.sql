-- =============================================================================
-- 基于 RAG 的企业内部知识库客服系统 - MySQL DDL (仅建表; 种子数据由 DataSeeder 幂等写入)
-- spring.sql.init.mode=never, 表结构由下方脚本手动/初始化执行一次; 应用启动幂等建表兜底
-- 约定: 主键自增 id BIGINT; 统一 created_at/updated_at; 表名/字段蛇形, 实体驼峰
-- =============================================================================

create table if not exists user (
  id            bigint auto_increment primary key,
  username      varchar(64)  not null,
  password_hash varchar(128) not null,          -- BCrypt
  real_name     varchar(64)  null,
  email         varchar(128) null,
  phone         varchar(32)  null,
  department_id bigint       null,
  role_id       bigint       not null,
  status        tinyint      not null default 1, -- 1正常 0停用
  created_at    datetime     not null default current_timestamp,
  updated_at    datetime     not null default current_timestamp on update current_timestamp,
  unique key uk_username (username),
  key idx_department (department_id), key idx_role (role_id)
);

create table if not exists department (
  id bigint auto_increment primary key,
  name varchar(64) not null, parent_id bigint null,
  description varchar(255) null,
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp
);

create table if not exists role (
  id bigint auto_increment primary key,
  code varchar(32) not null,                -- EMPLOYEE / DEPT_ADMIN / SYS_ADMIN
  name varchar(64) not null,
  created_at datetime not null default current_timestamp,
  unique key uk_code (code)
);

create table if not exists permission (
  id bigint auto_increment primary key,
  role_id bigint not null,
  resource varchar(64) not null,           -- 如 kb:finance / chat:ask
  access_level varchar(32) not null,       -- PUBLIC / DEPT / CONFIDENTIAL
  description varchar(255) null,
  created_at datetime not null default current_timestamp,
  key idx_role (role_id)
);

create table if not exists knowledge_base (
  id bigint auto_increment primary key,
  name varchar(128) not null,
  department_id bigint null,               -- 可空=全公司
  description varchar(255) null,
  permission_level varchar(32) not null default 'PUBLIC',
  created_by bigint null,
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  key idx_department (department_id)
);

create table if not exists document (
  id bigint auto_increment primary key,
  kb_id bigint not null,
  filename varchar(255) not null,
  file_path varchar(512) not null,
  file_hash varchar(64) null,              -- 文件内容 SHA-256，用于同库查重
  file_type varchar(16) not null,          -- md/pdf/docx/txt
  size bigint not null default 0,
  status varchar(16) not null default 'PARSING', -- PARSING/READY/FAILED
  chunk_count int not null default 0,
  permission_level varchar(32) not null default 'PUBLIC',
  department_id bigint null,                     -- 阶段4: 文档级所属部门(上传时快照自知识库; 权限设置可独立修改), 可空=全司
  uploaded_by bigint null,
  cleaned_text longtext null,                    -- 功能扩展01: 清洗后全文(引用溯源/原文高亮用); 解析失败可为 null
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  key idx_kb (kb_id)
);

create table if not exists conversation (
  id bigint auto_increment primary key,
  user_id bigint not null,
  title varchar(128) null,
  department_id bigint null,
  status tinyint not null default 1,       -- 1 正常 / 0 已归档
  created_at datetime not null default current_timestamp,
  updated_at datetime not null default current_timestamp on update current_timestamp,
  key idx_user (user_id)
);

create table if not exists message (
  id bigint auto_increment primary key,
  conversation_id bigint not null,
  role varchar(16) not null,               -- USER / ASSISTANT
  content text not null,
  sources text null,                       -- JSON: [{"documentId","title","chunkId","similarity"}]
  similarity double default null,
  answered tinyint default null,           -- 1 已回答 / 0 拒答
  response_ms bigint default null,         -- 阶段5: ASSISTANT 行 RAG 生成耗时(ms), 用于统计 avgResponseMs
  created_at datetime not null default current_timestamp,
  key idx_conversation (conversation_id)
);

create table if not exists feedback (
  id bigint auto_increment primary key,
  message_id bigint not null,
  user_id bigint not null,
  rating varchar(8) not null,              -- UP / DOWN
  reason varchar(255) null,
  created_at datetime not null default current_timestamp,
  key idx_message (message_id), key idx_user (user_id)
);

create table if not exists qa_statistics (
  id bigint auto_increment primary key,
  stat_date date not null,
  qa_total int not null default 0,
  answered_count int not null default 0,
  not_answered_count int not null default 0,
  hit_rate double,
  avg_response_ms int,
  category_distribution text,             -- JSON
  unique key uk_stat_date (stat_date)
);