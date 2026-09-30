-- platform 域 PostgreSQL 基线（DB2 生成，语句级过滤）
-- 来源：RuoYi-Vue-Plus v5.5.1 script/sql/postgres/{postgres_ry_vue_5.X.sql, postgres_ry_workflow.sql}
--       commit b58085fde121109d40d019402e84cc0dbeabdf67
-- 处理：整段保留上游 DDL；剔除 gen_*/test_* 的全部语句（CREATE TABLE / COMMENT / INDEX / 约束）；
--       剔除全部种子（INSERT/DELETE/setval）与全部 DROP TABLE（35 §6 禁止生产初始化执行带 DROP 的参考脚本）；
--       追加 ruoyi-ai 自有列 sys_user.open_id 与自有表 sys_url。
-- 不在此处创建 vector 扩展：实测 CREATE EXTENSION vector 需要超级账号，属 bootstrap 步骤。
-- ----------------------------
-- 第三方平台授权表
-- ----------------------------
create table sys_social
(
    id                 int8             not null,
    user_id            int8             not null,
    tenant_id          varchar(20)      default '000000'::varchar,
    auth_id            varchar(255)     not null,
    source             varchar(255)     not null,
    open_id            varchar(255)     default null::varchar,
    user_name          varchar(30)      not null,
    nick_name          varchar(30)      default ''::varchar,
    email              varchar(255)     default ''::varchar,
    avatar             varchar(500)     default ''::varchar,
    access_token       varchar(2000)    not null,
    expire_in          int8             default null,
    refresh_token      varchar(2000)    default null::varchar,
    access_code        varchar(255)     default null::varchar,
    union_id           varchar(255)     default null::varchar,
    scope              varchar(255)     default null::varchar,
    token_type         varchar(255)     default null::varchar,
    id_token           varchar(2000)    default null::varchar,
    mac_algorithm      varchar(255)     default null::varchar,
    mac_key            varchar(255)     default null::varchar,
    code               varchar(255)     default null::varchar,
    oauth_token        varchar(255)     default null::varchar,
    oauth_token_secret varchar(255)     default null::varchar,
    create_dept        int8,
    create_by          int8,
    create_time        timestamp,
    update_by          int8,
    update_time        timestamp,
    del_flag           char             default '0'::bpchar,
    constraint "pk_sys_social" primary key (id)
);

comment on table   sys_social                   is '社会化关系表';

comment on column  sys_social.id                is '主键';

comment on column  sys_social.user_id           is '用户ID';

comment on column  sys_social.tenant_id         is '租户id';

comment on column  sys_social.auth_id           is '平台+平台唯一id';

comment on column  sys_social.source            is '用户来源';

comment on column  sys_social.open_id           is '平台编号唯一id';

comment on column  sys_social.user_name         is '登录账号';

comment on column  sys_social.nick_name         is '用户昵称';

comment on column  sys_social.email             is '用户邮箱';

comment on column  sys_social.avatar            is '头像地址';

comment on column  sys_social.access_token      is '用户的授权令牌';

comment on column  sys_social.expire_in         is '用户的授权令牌的有效期，部分平台可能没有';

comment on column  sys_social.refresh_token     is '刷新令牌，部分平台可能没有';

comment on column  sys_social.access_code       is '平台的授权信息，部分平台可能没有';

comment on column  sys_social.union_id          is '用户的 unionid';

comment on column  sys_social.scope             is '授予的权限，部分平台可能没有';

comment on column  sys_social.token_type        is '个别平台的授权信息，部分平台可能没有';

comment on column  sys_social.id_token          is 'id token，部分平台可能没有';

comment on column  sys_social.mac_algorithm     is '小米平台用户的附带属性，部分平台可能没有';

comment on column  sys_social.mac_key           is '小米平台用户的附带属性，部分平台可能没有';

comment on column  sys_social.code              is '用户的授权code，部分平台可能没有';

comment on column  sys_social.oauth_token       is 'Twitter平台用户的附带属性，部分平台可能没有';

comment on column  sys_social.oauth_token_secret is 'Twitter平台用户的附带属性，部分平台可能没有';

comment on column  sys_social.create_dept       is '创建部门';

comment on column  sys_social.create_by         is '创建者';

comment on column  sys_social.create_time       is '创建时间';

comment on column  sys_social.update_by         is '更新者';

comment on column  sys_social.update_time       is '更新时间';

comment on column  sys_social.del_flag          is '删除标志（0代表存在 1代表删除）';

-- ----------------------------
-- 租户表
-- ----------------------------
create table if not exists sys_tenant
(
    id                int8,
    tenant_id         varchar(20)   not null,
    contact_user_name varchar(20)   default null::varchar,
    contact_phone     varchar(20)   default null::varchar,
    company_name      varchar(30)   default null::varchar,
    license_number    varchar(30)   default null::varchar,
    address           varchar(200)  default null::varchar,
    intro             varchar(200)  default null::varchar,
    domain            varchar(200)  default null::varchar,
    remark            varchar(200)  default null::varchar,
    package_id        int8,
    expire_time       timestamp,
    account_count     int4          default -1,
    status            char          default '0'::bpchar,
    del_flag          char          default '0'::bpchar,
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    constraint "pk_sys_tenant" primary key (id)
);

comment on table   sys_tenant                    is '租户表';

comment on column  sys_tenant.tenant_id          is '租户编号';

comment on column  sys_tenant.contact_phone      is '联系电话';

comment on column  sys_tenant.company_name       is '企业名称';

comment on column  sys_tenant.company_name       is '联系人';

comment on column  sys_tenant.license_number     is '统一社会信用代码';

comment on column  sys_tenant.address            is '地址';

comment on column  sys_tenant.intro              is '企业简介';

comment on column  sys_tenant.domain             is '域名';

comment on column  sys_tenant.remark             is '备注';

comment on column  sys_tenant.package_id         is '租户套餐编号';

comment on column  sys_tenant.expire_time        is '过期时间';

comment on column  sys_tenant.account_count      is '用户数量（-1不限制）';

comment on column  sys_tenant.status             is '租户状态（0正常 1停用）';

comment on column  sys_tenant.del_flag           is '删除标志（0代表存在 1代表删除）';

comment on column  sys_tenant.create_dept        is '创建部门';

comment on column  sys_tenant.create_by          is '创建者';

comment on column  sys_tenant.create_time        is '创建时间';

comment on column  sys_tenant.update_by          is '更新者';

comment on column  sys_tenant.update_time        is '更新时间';

-- ----------------------------
-- 租户套餐表
-- ----------------------------
create table if not exists sys_tenant_package
(
    package_id          int8,
    package_name        varchar(20)     default ''::varchar,
    menu_ids            varchar(3000)   default ''::varchar,
    remark              varchar(200)    default ''::varchar,
    menu_check_strictly bool            default true,
    status              char            default '0'::bpchar,
    del_flag            char            default '0'::bpchar,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    constraint "pk_sys_tenant_package" primary key (package_id)
);

comment on table   sys_tenant_package                    is '租户套餐表';

comment on column  sys_tenant_package.package_id         is '租户套餐id';

comment on column  sys_tenant_package.package_name       is '套餐名称';

comment on column  sys_tenant_package.menu_ids           is '关联菜单id';

comment on column  sys_tenant_package.remark             is '备注';

comment on column  sys_tenant_package.status             is '状态（0正常 1停用）';

comment on column  sys_tenant_package.del_flag           is '删除标志（0代表存在 1代表删除）';

comment on column  sys_tenant_package.create_dept        is '创建部门';

comment on column  sys_tenant_package.create_by          is '创建者';

comment on column  sys_tenant_package.create_time        is '创建时间';

comment on column  sys_tenant_package.update_by          is '更新者';

comment on column  sys_tenant_package.update_time        is '更新时间';

-- ----------------------------
-- 1、部门表
-- ----------------------------
create table if not exists sys_dept
(
    dept_id     int8,
    tenant_id   varchar(20) default '000000'::varchar,
    parent_id   int8        default 0,
    ancestors   varchar(500)default ''::varchar,
    dept_name   varchar(30) default ''::varchar,
    dept_category varchar(100) default null::varchar,
    order_num   int4        default 0,
    leader      int8        default null,
    phone       varchar(11) default null::varchar,
    email       varchar(50) default null::varchar,
    status      char        default '0'::bpchar,
    del_flag    char        default '0'::bpchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint "sys_dept_pk" primary key (dept_id)
);

comment on table sys_dept               is '部门表';

comment on column sys_dept.dept_id      is '部门ID';

comment on column sys_dept.tenant_id    is '租户编号';

comment on column sys_dept.parent_id    is '父部门ID';

comment on column sys_dept.ancestors    is '祖级列表';

comment on column sys_dept.dept_name    is '部门名称';

comment on column sys_dept.dept_category    is '部门类别编码';

comment on column sys_dept.order_num    is '显示顺序';

comment on column sys_dept.leader       is '负责人';

comment on column sys_dept.phone        is '联系电话';

comment on column sys_dept.email        is '邮箱';

comment on column sys_dept.status       is '部门状态（0正常 1停用）';

comment on column sys_dept.del_flag     is '删除标志（0代表存在 1代表删除）';

comment on column sys_dept.create_dept  is '创建部门';

comment on column sys_dept.create_by    is '创建者';

comment on column sys_dept.create_time  is '创建时间';

comment on column sys_dept.update_by    is '更新者';

comment on column sys_dept.update_time  is '更新时间';

-- ----------------------------
-- 2、用户信息表
-- ----------------------------
create table if not exists sys_user
(
    user_id     int8,
    tenant_id   varchar(20)  default '000000'::varchar,
    dept_id     int8,
    user_name   varchar(30)  not null,
    nick_name   varchar(30)  not null,
    user_type   varchar(10)  default 'sys_user'::varchar,
    email       varchar(50)  default ''::varchar,
    phonenumber varchar(11)  default ''::varchar,
    sex         char         default '0'::bpchar,
    avatar      int8,
    password    varchar(100) default ''::varchar,
    status      char         default '0'::bpchar,
    del_flag    char         default '0'::bpchar,
    login_ip    varchar(128) default ''::varchar,
    login_date  timestamp,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint "sys_user_pk" primary key (user_id)
);

comment on table sys_user               is '用户信息表';

comment on column sys_user.user_id      is '用户ID';

comment on column sys_user.tenant_id    is '租户编号';

comment on column sys_user.dept_id      is '部门ID';

comment on column sys_user.user_name    is '用户账号';

comment on column sys_user.nick_name    is '用户昵称';

comment on column sys_user.user_type    is '用户类型（sys_user系统用户）';

comment on column sys_user.email        is '用户邮箱';

comment on column sys_user.phonenumber  is '手机号码';

comment on column sys_user.sex          is '用户性别（0男 1女 2未知）';

comment on column sys_user.avatar       is '头像地址';

comment on column sys_user.password     is '密码';

comment on column sys_user.status       is '帐号状态（0正常 1停用）';

comment on column sys_user.del_flag     is '删除标志（0代表存在 1代表删除）';

comment on column sys_user.login_ip     is '最后登陆IP';

comment on column sys_user.login_date   is '最后登陆时间';

comment on column sys_user.create_dept  is '创建部门';

comment on column sys_user.create_by    is '创建者';

comment on column sys_user.create_time  is '创建时间';

comment on column sys_user.update_by    is '更新者';

comment on column sys_user.update_time  is '更新时间';

comment on column sys_user.remark       is '备注';

-- ----------------------------
-- 3、岗位信息表
-- ----------------------------
create table if not exists sys_post
(
    post_id     int8,
    tenant_id   varchar(20) default '000000'::varchar,
    dept_id     int8,
    post_code   varchar(64) not null,
    post_category   varchar(100) default null,
    post_name   varchar(50) not null,
    post_sort   int4        not null,
    status      char        not null,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint "sys_post_pk" primary key (post_id)
);

comment on table sys_post               is '岗位信息表';

comment on column sys_post.post_id      is '岗位ID';

comment on column sys_post.tenant_id    is '租户编号';

comment on column sys_post.dept_id      is '部门id';

comment on column sys_post.post_code    is '岗位编码';

comment on column sys_post.post_category is '岗位类别编码';

comment on column sys_post.post_name    is '岗位名称';

comment on column sys_post.post_sort    is '显示顺序';

comment on column sys_post.status       is '状态（0正常 1停用）';

comment on column sys_post.create_dept  is '创建部门';

comment on column sys_post.create_by    is '创建者';

comment on column sys_post.create_time  is '创建时间';

comment on column sys_post.update_by    is '更新者';

comment on column sys_post.update_time  is '更新时间';

comment on column sys_post.remark       is '备注';

-- ----------------------------
-- 4、角色信息表
-- ----------------------------
create table if not exists sys_role
(
    role_id             int8,
    tenant_id           varchar(20)  default '000000'::varchar,
    role_name           varchar(30)  not null,
    role_key            varchar(100) not null,
    role_sort           int4         not null,
    data_scope          char         default '1'::bpchar,
    menu_check_strictly bool         default true,
    dept_check_strictly bool         default true,
    status              char         not null,
    del_flag            char         default '0'::bpchar,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    remark              varchar(500) default null::varchar,
    constraint "sys_role_pk" primary key (role_id)
);

comment on table sys_role                       is '角色信息表';

comment on column sys_role.role_id              is '角色ID';

comment on column sys_role.tenant_id            is '租户编号';

comment on column sys_role.role_name            is '角色名称';

comment on column sys_role.role_key             is '角色权限字符串';

comment on column sys_role.role_sort            is '显示顺序';

comment on column sys_role.data_scope           is '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限 6：部门及以下或本人数据权限）';

comment on column sys_role.menu_check_strictly  is '菜单树选择项是否关联显示';

comment on column sys_role.dept_check_strictly  is '部门树选择项是否关联显示';

comment on column sys_role.status               is '角色状态（0正常 1停用）';

comment on column sys_role.del_flag             is '删除标志（0代表存在 1代表删除）';

comment on column sys_role.create_dept          is '创建部门';

comment on column sys_role.create_by            is '创建者';

comment on column sys_role.create_time          is '创建时间';

comment on column sys_role.update_by            is '更新者';

comment on column sys_role.update_time          is '更新时间';

comment on column sys_role.remark               is '备注';

-- ----------------------------
-- 5、菜单权限表
-- ----------------------------
create table if not exists sys_menu
(
    menu_id     int8,
    menu_name   varchar(50) not null,
    parent_id   int8         default 0,
    order_num   int4         default 0,
    path        varchar(200) default ''::varchar,
    component   varchar(255) default null::varchar,
    query_param varchar(255) default null::varchar,
    is_frame    char         default '1'::bpchar,
    is_cache    char         default '0'::bpchar,
    menu_type   char         default ''::bpchar,
    visible     char         default '0'::bpchar,
    status      char         default '0'::bpchar,
    perms       varchar(100) default null::varchar,
    icon        varchar(100) default '#'::varchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default ''::varchar,
    constraint "sys_menu_pk" primary key (menu_id)
);

comment on table sys_menu               is '菜单权限表';

comment on column sys_menu.menu_id      is '菜单ID';

comment on column sys_menu.menu_name    is '菜单名称';

comment on column sys_menu.parent_id    is '父菜单ID';

comment on column sys_menu.order_num    is '显示顺序';

comment on column sys_menu.path         is '路由地址';

comment on column sys_menu.component    is '组件路径';

comment on column sys_menu.query_param  is '路由参数';

comment on column sys_menu.is_frame     is '是否为外链（0是 1否）';

comment on column sys_menu.is_cache     is '是否缓存（0缓存 1不缓存）';

comment on column sys_menu.menu_type    is '菜单类型（M目录 C菜单 F按钮）';

comment on column sys_menu.visible      is '显示状态（0显示 1隐藏）';

comment on column sys_menu.status       is '菜单状态（0正常 1停用）';

comment on column sys_menu.perms        is '权限标识';

comment on column sys_menu.icon         is '菜单图标';

comment on column sys_menu.create_dept  is '创建部门';

comment on column sys_menu.create_by    is '创建者';

comment on column sys_menu.create_time  is '创建时间';

comment on column sys_menu.update_by    is '更新者';

comment on column sys_menu.update_time  is '更新时间';

comment on column sys_menu.remark       is '备注';

-- ----------------------------
-- 6、用户和角色关联表  用户N-1角色
-- ----------------------------
create table if not exists sys_user_role
(
    user_id int8 not null,
    role_id int8 not null,
    constraint sys_user_role_pk primary key (user_id, role_id)
);

comment on table sys_user_role              is '用户和角色关联表';

comment on column sys_user_role.user_id     is '用户ID';

comment on column sys_user_role.role_id     is '角色ID';

-- ----------------------------
-- 7、角色和菜单关联表  角色1-N菜单
-- ----------------------------
create table if not exists sys_role_menu
(
    role_id int8 not null,
    menu_id int8 not null,
    constraint sys_role_menu_pk primary key (role_id, menu_id)
);

comment on table sys_role_menu              is '角色和菜单关联表';

comment on column sys_role_menu.role_id     is '角色ID';

comment on column sys_role_menu.menu_id     is '菜单ID';

-- ----------------------------
-- 8、角色和部门关联表  角色1-N部门
-- ----------------------------
create table if not exists sys_role_dept
(
    role_id int8 not null,
    dept_id int8 not null,
    constraint sys_role_dept_pk primary key (role_id, dept_id)
);

comment on table sys_role_dept              is '角色和部门关联表';

comment on column sys_role_dept.role_id     is '角色ID';

comment on column sys_role_dept.dept_id     is '部门ID';

-- ----------------------------
-- 9、用户与岗位关联表  用户1-N岗位
-- ----------------------------
create table if not exists sys_user_post
(
    user_id int8 not null,
    post_id int8 not null,
    constraint sys_user_post_pk primary key (user_id, post_id)
);

comment on table sys_user_post              is '用户与岗位关联表';

comment on column sys_user_post.user_id     is '用户ID';

comment on column sys_user_post.post_id     is '岗位ID';

-- ----------------------------
-- 10、操作日志记录
-- ----------------------------
create table if not exists sys_oper_log
(
    oper_id        int8,
    tenant_id      varchar(20)   default '000000'::varchar,
    title          varchar(50)   default ''::varchar,
    business_type  int4          default 0,
    method         varchar(100)  default ''::varchar,
    request_method varchar(10)   default ''::varchar,
    operator_type  int4          default 0,
    oper_name      varchar(50)   default ''::varchar,
    dept_name      varchar(50)   default ''::varchar,
    oper_url       varchar(255)  default ''::varchar,
    oper_ip        varchar(128)  default ''::varchar,
    oper_location  varchar(255)  default ''::varchar,
    oper_param     varchar(4000) default ''::varchar,
    json_result    varchar(4000) default ''::varchar,
    status         int4          default 0,
    error_msg      varchar(4000) default ''::varchar,
    oper_time      timestamp,
    cost_time      int8          default 0,
    constraint sys_oper_log_pk primary key (oper_id)
);

create index idx_sys_oper_log_bt ON sys_oper_log (business_type);

create index idx_sys_oper_log_s ON sys_oper_log (status);

create index idx_sys_oper_log_ot ON sys_oper_log (oper_time);

comment on table sys_oper_log                   is '操作日志记录';

comment on column sys_oper_log.oper_id          is '日志主键';

comment on column sys_oper_log.tenant_id        is '租户编号';

comment on column sys_oper_log.title            is '模块标题';

comment on column sys_oper_log.business_type    is '业务类型（0其它 1新增 2修改 3删除）';

comment on column sys_oper_log.method           is '方法名称';

comment on column sys_oper_log.request_method   is '请求方式';

comment on column sys_oper_log.operator_type    is '操作类别（0其它 1后台用户 2手机端用户）';

comment on column sys_oper_log.oper_name        is '操作人员';

comment on column sys_oper_log.dept_name        is '部门名称';

comment on column sys_oper_log.oper_url         is '请求URL';

comment on column sys_oper_log.oper_ip          is '主机地址';

comment on column sys_oper_log.oper_location    is '操作地点';

comment on column sys_oper_log.oper_param       is '请求参数';

comment on column sys_oper_log.json_result      is '返回参数';

comment on column sys_oper_log.status           is '操作状态（0正常 1异常）';

comment on column sys_oper_log.error_msg        is '错误消息';

comment on column sys_oper_log.oper_time        is '操作时间';

comment on column sys_oper_log.cost_time        is '消耗时间';

-- ----------------------------
-- 11、字典类型表
-- ----------------------------
create table if not exists sys_dict_type
(
    dict_id     int8,
    tenant_id   varchar(20)  default '000000'::varchar,
    dict_name   varchar(100) default ''::varchar,
    dict_type   varchar(100) default ''::varchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint sys_dict_type_pk primary key (dict_id)
);

create unique index sys_dict_type_index1 ON sys_dict_type (tenant_id, dict_type);

comment on table sys_dict_type                  is '字典类型表';

comment on column sys_dict_type.dict_id         is '字典主键';

comment on column sys_dict_type.tenant_id       is '租户编号';

comment on column sys_dict_type.dict_name       is '字典名称';

comment on column sys_dict_type.dict_type       is '字典类型';

comment on column sys_dict_type.create_dept     is '创建部门';

comment on column sys_dict_type.create_by       is '创建者';

comment on column sys_dict_type.create_time     is '创建时间';

comment on column sys_dict_type.update_by       is '更新者';

comment on column sys_dict_type.update_time     is '更新时间';

comment on column sys_dict_type.remark          is '备注';

-- ----------------------------
-- 12、字典数据表
-- ----------------------------
create table if not exists sys_dict_data
(
    dict_code   int8,
    tenant_id   varchar(20)  default '000000'::varchar,
    dict_sort   int4         default 0,
    dict_label  varchar(100) default ''::varchar,
    dict_value  varchar(100) default ''::varchar,
    dict_type   varchar(100) default ''::varchar,
    css_class   varchar(100) default null::varchar,
    list_class  varchar(100) default null::varchar,
    is_default  char         default 'N'::bpchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint sys_dict_data_pk primary key (dict_code)
);

comment on table sys_dict_data                  is '字典数据表';

comment on column sys_dict_data.dict_code       is '字典编码';

comment on column sys_dict_type.tenant_id       is '租户编号';

comment on column sys_dict_data.dict_sort       is '字典排序';

comment on column sys_dict_data.dict_label      is '字典标签';

comment on column sys_dict_data.dict_value      is '字典键值';

comment on column sys_dict_data.dict_type       is '字典类型';

comment on column sys_dict_data.css_class       is '样式属性（其他样式扩展）';

comment on column sys_dict_data.list_class      is '表格回显样式';

comment on column sys_dict_data.is_default      is '是否默认（Y是 N否）';

comment on column sys_dict_data.create_dept     is '创建部门';

comment on column sys_dict_data.create_by       is '创建者';

comment on column sys_dict_data.create_time     is '创建时间';

comment on column sys_dict_data.update_by       is '更新者';

comment on column sys_dict_data.update_time     is '更新时间';

comment on column sys_dict_data.remark          is '备注';

-- ----------------------------
-- 13、参数配置表
-- ----------------------------
create table if not exists sys_config
(
    config_id    int8,
    tenant_id    varchar(20)  default '000000'::varchar,
    config_name  varchar(100) default ''::varchar,
    config_key   varchar(100) default ''::varchar,
    config_value varchar(500) default ''::varchar,
    config_type  char         default 'N'::bpchar,
    create_dept  int8,
    create_by    int8,
    create_time  timestamp,
    update_by    int8,
    update_time  timestamp,
    remark       varchar(500) default null::varchar,
    constraint sys_config_pk primary key (config_id)
);

comment on table sys_config                 is '参数配置表';

comment on column sys_config.config_id      is '参数主键';

comment on column sys_config.tenant_id      is '租户编号';

comment on column sys_config.config_name    is '参数名称';

comment on column sys_config.config_key     is '参数键名';

comment on column sys_config.config_value   is '参数键值';

comment on column sys_config.config_type    is '系统内置（Y是 N否）';

comment on column sys_config.create_dept    is '创建部门';

comment on column sys_config.create_by      is '创建者';

comment on column sys_config.create_time    is '创建时间';

comment on column sys_config.update_by      is '更新者';

comment on column sys_config.update_time    is '更新时间';

comment on column sys_config.remark         is '备注';

-- ----------------------------
-- 14、系统访问记录
-- ----------------------------
create table if not exists sys_logininfor
(
    info_id        int8,
    tenant_id      varchar(20)  default '000000'::varchar,
    user_name      varchar(50)  default ''::varchar,
    client_key     varchar(32)  default ''::varchar,
    device_type    varchar(32)  default ''::varchar,
    ipaddr         varchar(128) default ''::varchar,
    login_location varchar(255) default ''::varchar,
    browser        varchar(50)  default ''::varchar,
    os             varchar(50)  default ''::varchar,
    status         char         default '0'::bpchar,
    msg            varchar(255) default ''::varchar,
    login_time     timestamp,
    constraint sys_logininfor_pk primary key (info_id)
);

create index idx_sys_logininfor_s ON sys_logininfor (status);

create index idx_sys_logininfor_lt ON sys_logininfor (login_time);

comment on table sys_logininfor                 is '系统访问记录';

comment on column sys_logininfor.info_id        is '访问ID';

comment on column sys_logininfor.tenant_id      is '租户编号';

comment on column sys_logininfor.user_name      is '用户账号';

comment on column sys_logininfor.client_key     is '客户端';

comment on column sys_logininfor.device_type    is '设备类型';

comment on column sys_logininfor.ipaddr         is '登录IP地址';

comment on column sys_logininfor.login_location is '登录地点';

comment on column sys_logininfor.browser        is '浏览器类型';

comment on column sys_logininfor.os             is '操作系统';

comment on column sys_logininfor.status         is '登录状态（0成功 1失败）';

comment on column sys_logininfor.msg            is '提示消息';

comment on column sys_logininfor.login_time     is '访问时间';

-- ----------------------------
-- 17、通知公告表
-- ----------------------------
create table if not exists sys_notice
(
    notice_id      int8,
    tenant_id      varchar(20)  default '000000'::varchar,
    notice_title   varchar(50)  not null,
    notice_type    char         not null,
    notice_content text,
    status         char         default '0'::bpchar,
    create_dept    int8,
    create_by      int8,
    create_time    timestamp,
    update_by      int8,
    update_time    timestamp,
    remark         varchar(255) default null::varchar,
    constraint sys_notice_pk primary key (notice_id)
);

comment on table sys_notice                 is '通知公告表';

comment on column sys_notice.notice_id      is '公告ID';

comment on column sys_notice.tenant_id      is '租户编号';

comment on column sys_notice.notice_title   is '公告标题';

comment on column sys_notice.notice_type    is '公告类型（1通知 2公告）';

comment on column sys_notice.notice_content is '公告内容';

comment on column sys_notice.status         is '公告状态（0正常 1关闭）';

comment on column sys_notice.create_dept    is '创建部门';

comment on column sys_notice.create_by      is '创建者';

comment on column sys_notice.create_time    is '创建时间';

comment on column sys_notice.update_by      is '更新者';

comment on column sys_notice.update_time    is '更新时间';

comment on column sys_notice.remark         is '备注';

-- ----------------------------
-- OSS对象存储表
-- ----------------------------
create table if not exists sys_oss
(
    oss_id        int8,
    tenant_id     varchar(20)  default '000000'::varchar,
    file_name     varchar(255) default ''::varchar not null,
    original_name varchar(255) default ''::varchar not null,
    file_suffix   varchar(10)  default ''::varchar not null,
    url           varchar(500) default ''::varchar not null,
    ext1          varchar(500) default ''::varchar,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    service       varchar(20)  default 'minio'::varchar,
    constraint sys_oss_pk primary key (oss_id)
);

comment on table sys_oss                    is 'OSS对象存储表';

comment on column sys_oss.oss_id            is '对象存储主键';

comment on column sys_oss.tenant_id         is '租户编码';

comment on column sys_oss.file_name         is '文件名';

comment on column sys_oss.original_name     is '原名';

comment on column sys_oss.file_suffix       is '文件后缀名';

comment on column sys_oss.url               is 'URL地址';

comment on column sys_oss.ext1              is '扩展字段';

comment on column sys_oss.create_by         is '上传人';

comment on column sys_oss.create_dept       is '创建部门';

comment on column sys_oss.create_time       is '创建时间';

comment on column sys_oss.update_by         is '更新者';

comment on column sys_oss.update_time       is '更新时间';

comment on column sys_oss.service           is '服务商';

-- ----------------------------
-- OSS对象存储动态配置表
-- ----------------------------
create table if not exists sys_oss_config
(
    oss_config_id int8,
    tenant_id     varchar(20)  default '000000'::varchar,
    config_key    varchar(20)  default ''::varchar not null,
    access_key    varchar(255) default ''::varchar,
    secret_key    varchar(255) default ''::varchar,
    bucket_name   varchar(255) default ''::varchar,
    prefix        varchar(255) default ''::varchar,
    endpoint      varchar(255) default ''::varchar,
    domain        varchar(255) default ''::varchar,
    is_https      char         default 'N'::bpchar,
    region        varchar(255) default ''::varchar,
    access_policy char(1)      default '1'::bpchar not null,
    status        char         default '1'::bpchar,
    ext1          varchar(255) default ''::varchar,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    remark        varchar(500) default ''::varchar,
    constraint sys_oss_config_pk primary key (oss_config_id)
);

comment on table sys_oss_config                 is '对象存储配置表';

comment on column sys_oss_config.oss_config_id  is '主键';

comment on column sys_oss_config.tenant_id      is '租户编码';

comment on column sys_oss_config.config_key     is '配置key';

comment on column sys_oss_config.access_key     is 'accessKey';

comment on column sys_oss_config.secret_key     is '秘钥';

comment on column sys_oss_config.bucket_name    is '桶名称';

comment on column sys_oss_config.prefix         is '前缀';

comment on column sys_oss_config.endpoint       is '访问站点';

comment on column sys_oss_config.domain         is '自定义域名';

comment on column sys_oss_config.is_https       is '是否https（Y=是,N=否）';

comment on column sys_oss_config.region         is '域';

comment on column sys_oss_config.access_policy  is '桶权限类型(0=private 1=public 2=custom)';

comment on column sys_oss_config.status         is '是否默认（0=是,1=否）';

comment on column sys_oss_config.ext1           is '扩展字段';

comment on column sys_oss_config.create_dept    is '创建部门';

comment on column sys_oss_config.create_by      is '创建者';

comment on column sys_oss_config.create_time    is '创建时间';

comment on column sys_oss_config.update_by      is '更新者';

comment on column sys_oss_config.update_time    is '更新时间';

comment on column sys_oss_config.remark         is '备注';

-- ----------------------------
-- 系统授权表
-- ----------------------------
create table sys_client (
    id                  int8,
    client_id           varchar(64)   default ''::varchar,
    client_key          varchar(32)   default ''::varchar,
    client_secret       varchar(255)  default ''::varchar,
    grant_type          varchar(255)  default ''::varchar,
    device_type         varchar(32)   default ''::varchar,
    active_timeout      int4          default 1800,
    timeout             int4          default 604800,
    status              char(1)       default '0'::bpchar,
    del_flag            char(1)       default '0'::bpchar,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    constraint sys_client_pk primary key (id)
);

comment on table sys_client                         is '系统授权表';

comment on column sys_client.id                     is '主键';

comment on column sys_client.client_id              is '客户端id';

comment on column sys_client.client_key             is '客户端key';

comment on column sys_client.client_secret          is '客户端秘钥';

comment on column sys_client.grant_type             is '授权类型';

comment on column sys_client.device_type            is '设备类型';

comment on column sys_client.active_timeout         is 'token活跃超时时间';

comment on column sys_client.timeout                is 'token固定超时';

comment on column sys_client.status                 is '状态（0正常 1停用）';

comment on column sys_client.del_flag               is '删除标志（0代表存在 1代表删除）';

comment on column sys_client.create_dept            is '创建部门';

comment on column sys_client.create_by              is '创建者';

comment on column sys_client.create_time            is '创建时间';

comment on column sys_client.update_by              is '更新者';

comment on column sys_client.update_time            is '更新时间';

CREATE TABLE flow_definition
(
    id              int8         NOT NULL,
    flow_code       varchar(40)  NOT NULL,
    flow_name       varchar(100) NOT NULL,
    model_value     varchar(40)  NOT NULL DEFAULT 'CLASSICS',
    category        varchar(100) NULL,
    "version"       varchar(20)  NOT NULL,
    is_publish      int2         NOT NULL DEFAULT 0,
    form_custom     bpchar(1)    NULL     DEFAULT 'N':: character varying,
    form_path       varchar(100) NULL,
    activity_status int2         NOT NULL DEFAULT 1,
    listener_type   varchar(100) NULL,
    listener_path   varchar(400) NULL,
    ext             varchar(500) NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL     DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL     DEFAULT '':: character varying,
    del_flag        bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id       varchar(40)  NULL,
    CONSTRAINT flow_definition_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_definition IS '流程定义表';

COMMENT ON COLUMN flow_definition.id IS '主键id';

COMMENT ON COLUMN flow_definition.flow_code IS '流程编码';

COMMENT ON COLUMN flow_definition.flow_name IS '流程名称';

COMMENT ON COLUMN flow_definition.model_value IS '设计器模型（CLASSICS经典模型 MIMIC仿钉钉模型）';

COMMENT ON COLUMN flow_definition.category IS '流程类别';

COMMENT ON COLUMN flow_definition."version" IS '流程版本';

COMMENT ON COLUMN flow_definition.is_publish IS '是否发布（0未发布 1已发布 9失效）';

COMMENT ON COLUMN flow_definition.form_custom IS '审批表单是否自定义（Y是 N否）';

COMMENT ON COLUMN flow_definition.form_path IS '审批表单路径';

COMMENT ON COLUMN flow_definition.activity_status IS '流程激活状态（0挂起 1激活）';

COMMENT ON COLUMN flow_definition.listener_type IS '监听器类型';

COMMENT ON COLUMN flow_definition.listener_path IS '监听器路径';

COMMENT ON COLUMN flow_definition.ext IS '扩展字段，预留给业务系统使用';

COMMENT ON COLUMN flow_definition.create_time IS '创建时间';

COMMENT ON COLUMN flow_definition.create_by IS '创建人';

COMMENT ON COLUMN flow_definition.update_time IS '更新时间';

COMMENT ON COLUMN flow_definition.update_by IS '更新人';

COMMENT ON COLUMN flow_definition.del_flag IS '删除标志';

COMMENT ON COLUMN flow_definition.tenant_id IS '租户id';

CREATE TABLE flow_node
(
    id              int8          NOT NULL,
    node_type       int2          NOT NULL,
    definition_id   int8          NOT NULL,
    node_code       varchar(100)  NOT NULL,
    node_name       varchar(100)  NULL,
    permission_flag varchar(200)  NULL,
    node_ratio      numeric(6, 3) NULL,
    coordinate      varchar(100)  NULL,
    any_node_skip   varchar(100)  NULL,
    listener_type   varchar(100)  NULL,
    listener_path   varchar(400)  NULL,
    handler_type    varchar(100)  NULL,
    handler_path    varchar(400)  NULL,
    form_custom     bpchar(1)     NULL DEFAULT 'N':: character varying,
    form_path       varchar(100)  NULL,
    "version"       varchar(20)   NOT NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL DEFAULT '':: character varying,
    ext             text         NULL,
    del_flag        bpchar(1)     NULL DEFAULT '0':: character varying,
    tenant_id       varchar(40)   NULL,
    CONSTRAINT flow_node_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_node IS '流程节点表';

COMMENT ON COLUMN flow_node.id IS '主键id';

COMMENT ON COLUMN flow_node.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_node.definition_id IS '流程定义id';

COMMENT ON COLUMN flow_node.node_code IS '流程节点编码';

COMMENT ON COLUMN flow_node.node_name IS '流程节点名称';

COMMENT ON COLUMN flow_node.permission_flag IS '权限标识（权限类型:权限标识，可以多个，用@@隔开)';

COMMENT ON COLUMN flow_node.node_ratio IS '流程签署比例值';

COMMENT ON COLUMN flow_node.coordinate IS '坐标';

COMMENT ON COLUMN flow_node.any_node_skip IS '任意结点跳转';

COMMENT ON COLUMN flow_node.listener_type IS '监听器类型';

COMMENT ON COLUMN flow_node.listener_path IS '监听器路径';

COMMENT ON COLUMN flow_node.handler_type IS '处理器类型';

COMMENT ON COLUMN flow_node.handler_path IS '处理器路径';

COMMENT ON COLUMN flow_node.form_custom IS '审批表单是否自定义（Y是 N否）';

COMMENT ON COLUMN flow_node.form_path IS '审批表单路径';

COMMENT ON COLUMN flow_node."version" IS '版本';

COMMENT ON COLUMN flow_node.create_time IS '创建时间';

COMMENT ON COLUMN flow_node.create_by IS '创建人';

COMMENT ON COLUMN flow_node.update_time IS '更新时间';

COMMENT ON COLUMN flow_node.update_by IS '更新人';

COMMENT ON COLUMN flow_node.ext IS '节点扩展属性';

COMMENT ON COLUMN flow_node.del_flag IS '删除标志';

COMMENT ON COLUMN flow_node.tenant_id IS '租户id';

CREATE TABLE flow_skip
(
    id             int8         NOT NULL,
    definition_id  int8         NOT NULL,
    now_node_code  varchar(100) NOT NULL,
    now_node_type  int2         NULL,
    next_node_code varchar(100) NOT NULL,
    next_node_type int2         NULL,
    skip_name      varchar(100) NULL,
    skip_type      varchar(40)  NULL,
    skip_condition varchar(200) NULL,
    coordinate     varchar(100) NULL,
    create_time    timestamp    NULL,
    create_by      varchar(64)  NULL DEFAULT '':: character varying,
    update_time    timestamp    NULL,
    update_by      varchar(64)  NULL DEFAULT '':: character varying,
    del_flag       bpchar(1)    NULL DEFAULT '0':: character varying,
    tenant_id      varchar(40)  NULL,
    CONSTRAINT flow_skip_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_skip IS '节点跳转关联表';

COMMENT ON COLUMN flow_skip.id IS '主键id';

COMMENT ON COLUMN flow_skip.definition_id IS '流程定义id';

COMMENT ON COLUMN flow_skip.now_node_code IS '当前流程节点的编码';

COMMENT ON COLUMN flow_skip.now_node_type IS '当前节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_skip.next_node_code IS '下一个流程节点的编码';

COMMENT ON COLUMN flow_skip.next_node_type IS '下一个节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_skip.skip_name IS '跳转名称';

COMMENT ON COLUMN flow_skip.skip_type IS '跳转类型（PASS审批通过 REJECT退回）';

COMMENT ON COLUMN flow_skip.skip_condition IS '跳转条件';

COMMENT ON COLUMN flow_skip.coordinate IS '坐标';

COMMENT ON COLUMN flow_skip.create_time IS '创建时间';

COMMENT ON COLUMN flow_skip.create_by IS '创建人';

COMMENT ON COLUMN flow_skip.update_time IS '更新时间';

COMMENT ON COLUMN flow_skip.update_by IS '更新人';

COMMENT ON COLUMN flow_skip.del_flag IS '删除标志';

COMMENT ON COLUMN flow_skip.tenant_id IS '租户id';

CREATE TABLE flow_instance
(
    id              int8         NOT NULL,
    definition_id   int8         NOT NULL,
    business_id     varchar(40)  NOT NULL,
    node_type       int2         NOT NULL,
    node_code       varchar(40)  NOT NULL,
    node_name       varchar(100) NULL,
    variable        text         NULL,
    flow_status     varchar(20)  NOT NULL,
    activity_status int2         NOT NULL DEFAULT 1,
    def_json        text         NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL DEFAULT '':: character varying,
    ext             varchar(500) NULL,
    del_flag        bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id       varchar(40)  NULL,
    CONSTRAINT flow_instance_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_instance IS '流程实例表';

COMMENT ON COLUMN flow_instance.id IS '主键id';

COMMENT ON COLUMN flow_instance.definition_id IS '对应flow_definition表的id';

COMMENT ON COLUMN flow_instance.business_id IS '业务id';

COMMENT ON COLUMN flow_instance.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_instance.node_code IS '流程节点编码';

COMMENT ON COLUMN flow_instance.node_name IS '流程节点名称';

COMMENT ON COLUMN flow_instance.variable IS '任务变量';

COMMENT ON COLUMN flow_instance.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';

COMMENT ON COLUMN flow_instance.activity_status IS '流程激活状态（0挂起 1激活）';

COMMENT ON COLUMN flow_instance.def_json IS '流程定义json';

COMMENT ON COLUMN flow_instance.create_time IS '创建时间';

COMMENT ON COLUMN flow_instance.create_by IS '创建人';

COMMENT ON COLUMN flow_instance.update_time IS '更新时间';

COMMENT ON COLUMN flow_instance.update_by IS '更新人';

COMMENT ON COLUMN flow_instance.ext IS '扩展字段，预留给业务系统使用';

COMMENT ON COLUMN flow_instance.del_flag IS '删除标志';

COMMENT ON COLUMN flow_instance.tenant_id IS '租户id';

CREATE TABLE flow_task
(
    id            int8         NOT NULL,
    definition_id int8         NOT NULL,
    instance_id   int8         NOT NULL,
    node_code     varchar(100) NOT NULL,
    node_name     varchar(100) NULL,
    node_type     int2         NOT NULL,
    flow_status   varchar(20)  NOT NULL,
    form_custom   bpchar(1)    NULL DEFAULT 'N':: character varying,
    form_path     varchar(100) NULL,
    create_time   timestamp    NULL,
    create_by     varchar(64)  NULL DEFAULT '':: character varying,
    update_time   timestamp    NULL,
    update_by     varchar(64)  NULL DEFAULT '':: character varying,
    del_flag      bpchar(1)    NULL DEFAULT '0':: character varying,
    tenant_id     varchar(40)  NULL,
    CONSTRAINT flow_task_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_task IS '待办任务表';

COMMENT ON COLUMN flow_task.id IS '主键id';

COMMENT ON COLUMN flow_task.definition_id IS '对应flow_definition表的id';

COMMENT ON COLUMN flow_task.instance_id IS '对应flow_instance表的id';

COMMENT ON COLUMN flow_task.node_code IS '节点编码';

COMMENT ON COLUMN flow_task.node_name IS '节点名称';

COMMENT ON COLUMN flow_task.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_task.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';

COMMENT ON COLUMN flow_task.form_custom IS '审批表单是否自定义（Y是 N否）';

COMMENT ON COLUMN flow_task.form_path IS '审批表单路径';

COMMENT ON COLUMN flow_task.create_time IS '创建时间';

COMMENT ON COLUMN flow_task.create_by IS '创建人';

COMMENT ON COLUMN flow_task.update_time IS '更新时间';

COMMENT ON COLUMN flow_task.update_by IS '更新人';

COMMENT ON COLUMN flow_task.del_flag IS '删除标志';

COMMENT ON COLUMN flow_task.tenant_id IS '租户id';

CREATE TABLE flow_his_task
(
    id               int8         NOT NULL,
    definition_id    int8         NOT NULL,
    instance_id      int8         NOT NULL,
    task_id          int8         NOT NULL,
    node_code        varchar(100) NULL,
    node_name        varchar(100) NULL,
    node_type        int2         NULL,
    target_node_code varchar(200) NULL,
    target_node_name varchar(200) NULL,
    approver         varchar(40)  NULL,
    cooperate_type   int2         NOT NULL DEFAULT 0,
    collaborator     varchar(500)  NULL,
    skip_type        varchar(10)  NULL,
    flow_status      varchar(20)  NOT NULL,
    form_custom      bpchar(1)    NULL     DEFAULT 'N':: character varying,
    form_path        varchar(100) NULL,
    ext              text         NULL,
    message          varchar(500) NULL,
    variable         text         NULL,
    create_time      timestamp    NULL,
    update_time      timestamp    NULL,
    del_flag         bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id        varchar(40)  NULL,
    CONSTRAINT flow_his_task_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE flow_his_task IS '历史任务记录表';

COMMENT ON COLUMN flow_his_task.id IS '主键id';

COMMENT ON COLUMN flow_his_task.definition_id IS '对应flow_definition表的id';

COMMENT ON COLUMN flow_his_task.instance_id IS '对应flow_instance表的id';

COMMENT ON COLUMN flow_his_task.task_id IS '对应flow_task表的id';

COMMENT ON COLUMN flow_his_task.node_code IS '开始节点编码';

COMMENT ON COLUMN flow_his_task.node_name IS '开始节点名称';

COMMENT ON COLUMN flow_his_task.node_type IS '开始节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';

COMMENT ON COLUMN flow_his_task.target_node_code IS '目标节点编码';

COMMENT ON COLUMN flow_his_task.target_node_name IS '结束节点名称';

COMMENT ON COLUMN flow_his_task.approver IS '审批者';

COMMENT ON COLUMN flow_his_task.cooperate_type IS '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)';

COMMENT ON COLUMN flow_his_task.collaborator IS '协作人';

COMMENT ON COLUMN flow_his_task.skip_type IS '流转类型（PASS通过 REJECT退回 NONE无动作）';

COMMENT ON COLUMN flow_his_task.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';

COMMENT ON COLUMN flow_his_task.form_custom IS '审批表单是否自定义（Y是 N否）';

COMMENT ON COLUMN flow_his_task.form_path IS '审批表单路径';

COMMENT ON COLUMN flow_his_task.message IS '审批意见';

COMMENT ON COLUMN flow_his_task.variable IS '任务变量';

COMMENT ON COLUMN flow_his_task.ext IS '扩展字段，预留给业务系统使用';

COMMENT ON COLUMN flow_his_task.create_time IS '任务开始时间';

COMMENT ON COLUMN flow_his_task.update_time IS '审批完成时间';

COMMENT ON COLUMN flow_his_task.del_flag IS '删除标志';

COMMENT ON COLUMN flow_his_task.tenant_id IS '租户id';

CREATE TABLE flow_user
(
    id           int8        NOT NULL,
    "type"       bpchar(1)   NOT NULL,
    processed_by varchar(80) NULL,
    associated   int8        NOT NULL,
    create_time  timestamp   NULL,
    create_by    varchar(64)  NULL     DEFAULT '':: character varying,
    update_time  timestamp   NULL,
    update_by    varchar(64)  NULL DEFAULT '':: character varying,
    del_flag     bpchar(1)   NULL DEFAULT '0':: character varying,
    tenant_id    varchar(40) NULL,
    CONSTRAINT flow_user_pk PRIMARY KEY (id)
);

CREATE INDEX user_processed_type ON flow_user USING btree (processed_by, type);

CREATE INDEX user_associated_idx ON FLOW_USER USING btree (associated);

COMMENT ON TABLE flow_user IS '流程用户表';

COMMENT ON COLUMN flow_user.id IS '主键id';

COMMENT ON COLUMN flow_user."type" IS '人员类型（1待办任务的审批人权限 2待办任务的转办人权限 3待办任务的委托人权限）';

COMMENT ON COLUMN flow_user.processed_by IS '权限人';

COMMENT ON COLUMN flow_user.associated IS '任务表id';

COMMENT ON COLUMN flow_user.create_time IS '创建时间';

COMMENT ON COLUMN flow_user.create_by IS '创建人';

COMMENT ON COLUMN flow_user.update_time IS '更新时间';

COMMENT ON COLUMN flow_user.update_by IS '更新人';

COMMENT ON COLUMN flow_user.del_flag IS '删除标志';

COMMENT ON COLUMN flow_user.tenant_id IS '租户id';

-- ----------------------------
-- 流程分类表
-- ----------------------------
CREATE TABLE flow_category
(
    category_id   int8         NOT NULL,
    tenant_id     VARCHAR(20)  DEFAULT '000000'::varchar,
    parent_id     int8         DEFAULT 0,
    ancestors     VARCHAR(500) DEFAULT ''::varchar,
    category_name VARCHAR(30)  NOT NULL,
    order_num     INT          DEFAULT 0,
    del_flag      CHAR         DEFAULT '0'::bpchar,
    create_dept   int8,
    create_by     int8,
    create_time   TIMESTAMP,
    update_by     int8,
    update_time   TIMESTAMP,
    PRIMARY KEY (category_id)
);

COMMENT ON TABLE flow_category IS '流程分类';

COMMENT ON COLUMN flow_category.category_id IS '流程分类ID';

COMMENT ON COLUMN flow_category.tenant_id IS '租户编号';

COMMENT ON COLUMN flow_category.parent_id IS '父流程分类id';

COMMENT ON COLUMN flow_category.ancestors IS '祖级列表';

COMMENT ON COLUMN flow_category.category_name IS '流程分类名称';

COMMENT ON COLUMN flow_category.order_num IS '显示顺序';

COMMENT ON COLUMN flow_category.del_flag IS '删除标志（0代表存在 1代表删除）';

COMMENT ON COLUMN flow_category.create_dept IS '创建部门';

COMMENT ON COLUMN flow_category.create_by IS '创建者';

COMMENT ON COLUMN flow_category.create_time IS '创建时间';

COMMENT ON COLUMN flow_category.update_by IS '更新者';

COMMENT ON COLUMN flow_category.update_time IS '更新时间';

-- ----------------------------
-- 流程spel表达式定义表
-- ----------------------------
CREATE TABLE flow_spel (
    id int8 NOT NULL,
    component_name VARCHAR(255),
    method_name VARCHAR(255),
    method_params VARCHAR(255),
    view_spel VARCHAR(255),
    remark VARCHAR(255),
    status CHAR(1) DEFAULT '0',
    del_flag CHAR(1) DEFAULT '0',
    create_dept int8,
    create_by int8,
    create_time TIMESTAMP,
    update_by int8,
    update_time TIMESTAMP,
    PRIMARY KEY (id)
);

COMMENT ON TABLE flow_spel IS '流程spel表达式定义表';

COMMENT ON COLUMN flow_spel.id IS '主键id';

COMMENT ON COLUMN flow_spel.component_name IS '组件名称';

COMMENT ON COLUMN flow_spel.method_name IS '方法名';

COMMENT ON COLUMN flow_spel.method_params IS '参数';

COMMENT ON COLUMN flow_spel.view_spel IS '预览spel表达式';

COMMENT ON COLUMN flow_spel.remark IS '备注';

COMMENT ON COLUMN flow_spel.status IS '状态（0正常 1停用）';

COMMENT ON COLUMN flow_spel.del_flag IS '删除标志';

COMMENT ON COLUMN flow_spel.create_dept IS '创建部门';

COMMENT ON COLUMN flow_spel.create_by IS '创建者';

COMMENT ON COLUMN flow_spel.create_time IS '创建时间';

COMMENT ON COLUMN flow_spel.update_by IS '更新者';

COMMENT ON COLUMN flow_spel.update_time IS '更新时间';

-- ----------------------------
-- 流程实例业务扩展表
-- ----------------------------
CREATE TABLE flow_instance_biz_ext (
    id             int8,
    tenant_id      VARCHAR(20)   DEFAULT '000000',
    create_dept    int8,
    create_by      int8,
    create_time    TIMESTAMP,
    update_by      int8,
    update_time    TIMESTAMP,
    business_code  VARCHAR(255),
    business_title VARCHAR(1000),
    del_flag       CHAR(1)       DEFAULT '0',
    instance_id    int8,
    business_id    VARCHAR(255),
    PRIMARY KEY (id)
);

COMMENT ON TABLE flow_instance_biz_ext IS '流程实例业务扩展表';

COMMENT ON COLUMN flow_instance_biz_ext.id  IS '主键id';

COMMENT ON COLUMN flow_instance_biz_ext.tenant_id  IS '租户编号';

COMMENT ON COLUMN flow_instance_biz_ext.create_dept  IS '创建部门';

COMMENT ON COLUMN flow_instance_biz_ext.create_by  IS '创建者';

COMMENT ON COLUMN flow_instance_biz_ext.create_time  IS '创建时间';

COMMENT ON COLUMN flow_instance_biz_ext.update_by  IS '更新者';

COMMENT ON COLUMN flow_instance_biz_ext.update_time  IS '更新时间';

COMMENT ON COLUMN flow_instance_biz_ext.business_code  IS '业务编码';

COMMENT ON COLUMN flow_instance_biz_ext.business_title  IS '业务标题';

COMMENT ON COLUMN flow_instance_biz_ext.del_flag  IS '删除标志（0代表存在 1代表删除）';

COMMENT ON COLUMN flow_instance_biz_ext.instance_id  IS '流程实例Id';

COMMENT ON COLUMN flow_instance_biz_ext.business_id  IS '业务Id';

-- ===== ruoyi-ai 自有增补（依本地 MySQL 脚本逐列翻译）=====
ALTER TABLE sys_user ADD COLUMN open_id varchar(100) NULL;
COMMENT ON COLUMN sys_user.open_id IS '微信用户标识（ruoyi-ai 自有列，上游 v5.5.1 无）';

-- sys_url：上游无此表，依 docs/script/sql/update/2026-09-01-sys-url.sql 转 PG 形态。
-- AUTO_INCREMENT -> GENERATED BY DEFAULT AS IDENTITY；datetime -> timestamp；不迁移 utf8mb4_0900_ai_ci 排序规则。
-- sys_user.user_balance（账户余额）在此基线中未建：由 V3__restore_user_balance.sql 补回。
CREATE TABLE sys_url (
    url_id      bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id   varchar(20)  NOT NULL DEFAULT '000000',
    name        varchar(100) NOT NULL,
    url         varchar(500) NOT NULL,
    description varchar(500) NULL,
    sort_order  int          NULL DEFAULT 0,
    status      char(1)      NULL DEFAULT '0',
    create_dept bigint       NULL,
    create_by   bigint       NULL,
    create_time timestamp    NULL,
    update_by   bigint       NULL,
    update_time timestamp    NULL,
    remark      varchar(255) NULL
);
COMMENT ON TABLE sys_url IS 'URL 管理表';
