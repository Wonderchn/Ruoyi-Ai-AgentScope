/**
 * 平台系统管理 API（WP-039 面）。
 *
 * ## 路径来源（不要改路径前先读这段）
 *
 * 每一条路径都来自**实测扫描**，不是抄 `04-page-map.json`：
 * 工具 `D:/AI-project/.scratch/platform-embedded-impl/T7/tools/scan_admin_routes.py`
 * 只扫 `services/platform`（避开旧 `services/ai` 同名类），产出
 * `admin-controller-inventory.json`（**589 个 mapping 注解 → 593 条路由**，锚点 4 条）。
 * 已登记的路径错误反例：`/system/ossconfig/list` → `code=404`，真实前缀是 `/resource/oss/**`。
 *
 * ## 为什么是工厂而不是裸函数
 *
 * `apps/admin/tests/ts-loader.mjs` 用 Node 内置 test runner 直接跑 `.ts`，它**不认识 `@/`
 * 别名**（只对相对说明符补 `.ts`），也不渲染 `.vue`。所以凡是"要断言的请求形状"，
 * 都必须能被测试**注入 fetch** 后调用 —— 这就是 `createSystemApi(client)` 存在的理由：
 * 测试传自己造的 client，页面传 `@/utils/request` 的单例。
 * 这也保证**不出现第二套客户端**：`client` 是 `@ruoyi/platform-client` 的类型。
 *
 * ## 各实体差异（刻意保留，不是复制模板）
 *
 * | 实体 | 分页 | 删除 | 状态 | 特有操作 |
 * |---|---|---|---|---|
 * | role | 是 | `DELETE /{roleIds}` | `PUT /changeStatus` | `dataScope`(独立 body)、`deptTree/{id}`、authUser 三连 |
 * | dept | **否**（树） | `DELETE /{deptId}`（**单个**） | 无 | `list/exclude/{deptId}`、`optionselect` |
 * | post | 是 | `DELETE /{postIds}` | 无 | `deptTree`(岗位的数据范围树)、export |
 * | dictType | 是 | `DELETE /{dictIds}` | 无 | `optionselect`、`refreshCache`(**DELETE 动词**) |
 * | dictData | 是 | `DELETE /{dictCodes}`（**按 dictCode 不是 id**） | 无 | `type/{dictType}`（不分页） |
 * | config | 是 | `DELETE /{configIds}` | 无 | `updateByKey`(**PUT**)、`refreshCache`(**DELETE**)、`configKey/{key}`（**无权限注解**） |
 * | notice | 是 | `DELETE /{noticeIds}` | 无 | **无 export** |
 * | client | 是 | `DELETE /{ids}` | `PUT /changeStatus` | export；**无 deptTree** |
 *
 * 上表里加粗的每一格都是一个"照模板写就会写错"的点。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { PageParams } from '../../utils/list';

/** 平台通用状态：`'0'` 正常 / `'1'` 停用（若依约定，与 `utils/list.ts` 的 `statusTagType` 一致）。 */
export type PlatformStatus = '0' | '1';

/**
 * `POST /system/&lt;area&gt;/export` 的响应是 xlsx 二进制，**不是** `R<T>`，因此不能用 get/post 解包。
 *
 * 注意这里刻意不写通配路径字面量：`/system` + `*` + `/export` 里的 `*` + `/`
 * 会**提前闭合块注释**（实测：eslint 报 `Parsing error: Declaration or statement expected`）。
 */
export interface ExportRequest {
  query?: Record<string, string | number | boolean | undefined | null>;
  body?: unknown;
}

// ---------------------------------------------------------------------------
// 角色
// ---------------------------------------------------------------------------

export interface SysRoleVo {
  roleId?: string;
  roleName?: string;
  roleKey?: string;
  roleSort?: number;
  dataScope?: string;
  menuCheckStrictly?: boolean;
  deptCheckStrictly?: boolean;
  status?: string;
  remark?: string;
  createTime?: string;
}

export interface SysRoleQuery extends PageParams {
  roleName?: string;
  roleKey?: string;
  status?: string;
}

export interface SysRoleBody {
  roleId?: string;
  roleName: string;
  roleKey: string;
  roleSort: number;
  dataScope?: string;
  menuIds?: string[];
  deptIds?: string[];
  status?: string;
  remark?: string;
}

/** 数据权限范围（与后端 `SysRoleController.dataScope` 的 body 同形）。 */
export interface SysRoleDataScopeBody {
  roleId: string;
  dataScope: string;
  deptIds?: string[];
  deptCheckStrictly?: boolean;
}

// ---------------------------------------------------------------------------
// 部门
// ---------------------------------------------------------------------------

export interface SysDeptVo {
  deptId?: string;
  parentId?: string;
  deptName?: string;
  orderNum?: number;
  leader?: string;
  phone?: string;
  email?: string;
  status?: string;
  children?: SysDeptVo[];
}

export interface SysDeptQuery {
  deptName?: string;
  status?: string;
}

export interface SysDeptBody {
  deptId?: string;
  parentId: string;
  deptName: string;
  orderNum: number;
  leader?: string;
  phone?: string;
  email?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// 岗位
// ---------------------------------------------------------------------------

export interface SysPostVo {
  postId?: string;
  postCode?: string;
  postName?: string;
  postSort?: number;
  status?: string;
  remark?: string;
}

export interface SysPostQuery extends PageParams {
  postCode?: string;
  postName?: string;
  status?: string;
}

export interface SysPostBody {
  postId?: string;
  postCode: string;
  postName: string;
  postSort: number;
  status?: string;
  remark?: string;
}

// ---------------------------------------------------------------------------
// 字典
// ---------------------------------------------------------------------------

export interface SysDictTypeVo {
  dictId?: string;
  dictName?: string;
  dictType?: string;
  status?: string;
  remark?: string;
}

export interface SysDictTypeQuery extends PageParams {
  dictName?: string;
  dictType?: string;
  status?: string;
}

export interface SysDictTypeBody {
  dictId?: string;
  dictName: string;
  dictType: string;
  status?: string;
  remark?: string;
}

export interface SysDictDataVo {
  dictCode?: string;
  dictSort?: number;
  dictLabel?: string;
  dictValue?: string;
  dictType?: string;
  cssClass?: string;
  listClass?: string;
  isDefault?: string;
  status?: string;
  remark?: string;
}

export interface SysDictDataQuery extends PageParams {
  dictType?: string;
  dictLabel?: string;
  status?: string;
}

export interface SysDictDataBody {
  dictCode?: string;
  dictSort: number;
  dictLabel: string;
  dictValue: string;
  dictType: string;
  cssClass?: string;
  listClass?: string;
  isDefault?: string;
  status?: string;
  remark?: string;
}

// ---------------------------------------------------------------------------
// 参数配置
// ---------------------------------------------------------------------------

export interface SysConfigVo {
  configId?: string;
  configName?: string;
  configKey?: string;
  configValue?: string;
  configType?: string;
  remark?: string;
  createTime?: string;
}

export interface SysConfigQuery extends PageParams {
  configName?: string;
  configKey?: string;
  configType?: string;
}

export interface SysConfigBody {
  configId?: string;
  configName: string;
  configKey: string;
  configValue: string;
  configType?: string;
  remark?: string;
}

// ---------------------------------------------------------------------------
// 通知公告
// ---------------------------------------------------------------------------

export interface SysNoticeVo {
  noticeId?: string;
  noticeTitle?: string;
  noticeType?: string;
  noticeContent?: string;
  status?: string;
  createBy?: string;
  createTime?: string;
}

export interface SysNoticeQuery extends PageParams {
  noticeTitle?: string;
  createBy?: string;
  noticeType?: string;
}

export interface SysNoticeBody {
  noticeId?: string;
  noticeTitle: string;
  noticeType: string;
  noticeContent?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// 客户端
// ---------------------------------------------------------------------------

export interface SysClientVo {
  id?: string;
  clientId?: string;
  clientKey?: string;
  /** 服务端已在响应边界掩码为 `******`；**前端不得把它当明文用**。 */
  clientSecret?: string;
  grantType?: string;
  deviceType?: string;
  activeTimeout?: number;
  timeout?: number;
  status?: string;
}

export interface SysClientQuery extends PageParams {
  clientKey?: string;
  status?: string;
}

export interface SysClientBody {
  id?: string;
  clientId: string;
  clientKey: string;
  clientSecret?: string;
  grantType: string;
  deviceType?: string;
  activeTimeout?: number;
  timeout?: number;
  status?: string;
}

/** `PUT /system/role/changeStatus` 之类的状态切换 body（role/tenant/client 同形不同路径）。 */
export interface StatusBody {
  [key: string]: string | undefined;
}

// ---------------------------------------------------------------------------
// RW-15 补齐的 F01 子域类型（OSS / 个人中心 / 社交 / 短链）
// ---------------------------------------------------------------------------

/** `SysOssVo`：OSS 对象行（`ossId` 是雪花 Long ⇒ string）。 */
export interface SysOssVo {
  ossId?: string;
  fileName?: string;
  originalName?: string;
  fileSuffix?: string;
  url?: string;
  createTime?: string;
  createBy?: string;
  service?: string;
}

export interface SysOssQuery extends PageParams {
  fileName?: string;
  originalName?: string;
  service?: string;
}

/** `SysOssConfigVo`：OSS 配置行（**不含**密钥明文；服务端按 VO 边界决定掩码/省略）。 */
export interface SysOssConfigVo {
  ossConfigId?: string;
  configKey?: string;
  accessKey?: string;
  secretKey?: string;
  bucketName?: string;
  prefix?: string;
  endpoint?: string;
  region?: string;
  accessPolicy?: string;
  /** '0' 正常 / '1' 停用 */
  status?: string;
  remark?: string;
  createTime?: string;
}

export interface SysOssConfigQuery extends PageParams {
  configKey?: string;
  bucketName?: string;
  status?: string;
}

export interface SysOssConfigBody {
  ossConfigId?: string;
  configKey?: string;
  accessKey?: string;
  secretKey?: string;
  bucketName?: string;
  prefix?: string;
  endpoint?: string;
  region?: string;
  accessPolicy?: string;
  status?: string;
  remark?: string;
}

/** 个人中心（`ProfileVo`）：`{user, roleGroup, postGroup}`。 */
export interface SysProfileVo {
  user?: Record<string, unknown> | null;
  roleGroup?: string;
  postGroup?: string;
}

/** `SysUserProfileBo`：可改的本人资料字段。 */
export interface SysProfileBody {
  nickName?: string;
  email?: string;
  phonenumber?: string;
  sex?: string;
}

export interface SysSocialVo {
  id?: string;
  userId?: string;
  tenantId?: string;
  /** 第三方平台（如 gitee/wechat） */
  source?: string;
  openId?: string;
  userName?: string;
  nickName?: string;
  email?: string;
  avatar?: string;
  createTime?: string;
}

export interface SysUrlVo {
  urlId?: string;
  /** 短链后的完整 URL */
  shortUrl?: string;
  /** 原始 URL */
  url?: string;
  comment?: string;
  createTime?: string;
  createBy?: string;
  [key: string]: unknown;
}

export interface SysUrlShortcutVo {
  id?: string;
  name?: string;
  url?: string;
  icon?: string;
  [key: string]: unknown;
}

export interface SysUrlQuery extends PageParams {
  url?: string;
  comment?: string;
}

export interface SysUrlBody {
  urlId?: string;
  url?: string;
  comment?: string;
}

/** RW-15 新增子域的逐字权限串（后端 `@SaCheckPermission`）。 */
export const SYSTEM_F01_PERMISSIONS = {
  ossList: 'system:oss:list',
  ossQuery: 'system:oss:query',
  ossUpload: 'system:oss:upload',
  ossDownload: 'system:oss:download',
  ossRemove: 'system:oss:remove',
  ossConfigList: 'system:ossConfig:list',
  ossConfigAdd: 'system:ossConfig:add',
  ossConfigEdit: 'system:ossConfig:edit',
  ossConfigRemove: 'system:ossConfig:remove',
  urlList: 'system:url:list',
  urlQuery: 'system:url:query',
  urlAdd: 'system:url:add',
  urlEdit: 'system:url:edit',
  urlRemove: 'system:url:remove',
  cacheList: 'monitor:cache:list',
} as const;

export function createSystemApi(client: PlatformClient) {
  return {
    roles: {
      list: (query: SysRoleQuery) => client.getRows<SysRoleVo>('/system/role/list', { query: { ...query } }),
      get: (roleId: string) => client.get<SysRoleVo>(`/system/role/${encodeURIComponent(roleId)}`),
      create: (body: SysRoleBody) => client.post<unknown>('/system/role', { body }),
      update: (body: SysRoleBody) => client.put<unknown>('/system/role', { body }),
      remove: (roleIds: readonly string[]) =>
        client.del<unknown>(`/system/role/${roleIds.map(encodeURIComponent).join(',')}`),
      /** 状态是**独立端点**，且 body 只有 `{roleId, status}`（不要发整个角色）。 */
      changeStatus: (roleId: string, status: PlatformStatus) =>
        client.put<unknown>('/system/role/changeStatus', { body: { roleId, status } }),
      /** 数据权限走独立端点，body 与普通编辑**不同形**。 */
      dataScope: (body: SysRoleDataScopeBody) => client.put<unknown>('/system/role/dataScope', { body }),
      deptTree: (roleId: string) => client.get<unknown>(`/system/role/deptTree/${encodeURIComponent(roleId)}`),
      options: () => client.get<SysRoleVo[]>('/system/role/optionselect'),
      /** 导出返回 xlsx 二进制；**不要**走 getRows。 */
      exportUrl: () => '/system/role/export',
      menuTreeSelect: (roleId: string) =>
        client.get<unknown>(`/system/menu/roleMenuTreeselect/${encodeURIComponent(roleId)}`),
      authorizedUsers: (query: PageParams & { roleId: string; userName?: string; phonenumber?: string }) =>
        client.getRows<unknown>('/system/role/authUser/allocatedList', { query: { ...query } }),
      unauthorizedUsers: (query: PageParams & { roleId: string; userName?: string; phonenumber?: string }) =>
        client.getRows<unknown>('/system/role/authUser/unallocatedList', { query: { ...query } }),
      cancelAuthUser: (body: { roleId: string; userId: string }) =>
        client.put<unknown>('/system/role/authUser/cancel', { body }),
      cancelAuthUserAll: (body: { roleId: string; userIds: string }) =>
        client.put<unknown>('/system/role/authUser/cancelAll', { body }),
      selectAuthUserAll: (body: { roleId: string; userIds: string }) =>
        client.put<unknown>('/system/role/authUser/selectAll', { body }),
    },

    depts: {
      /** **不分页**：后端返回 `R<List<SysDeptVo>>`（树）。传 pageNum 不会报错但也没有 total。 */
      list: (query: SysDeptQuery) => client.get<SysDeptVo[]>('/system/dept/list', { query: { ...query } }),
      /** 排除某节点及其子树的部门列表（编辑部门时选父级用）。 */
      listExclude: (deptId: string) =>
        client.get<SysDeptVo[]>(`/system/dept/list/exclude/${encodeURIComponent(deptId)}`),
      get: (deptId: string) => client.get<SysDeptVo>(`/system/dept/${encodeURIComponent(deptId)}`),
      create: (body: SysDeptBody) => client.post<unknown>('/system/dept', { body }),
      update: (body: SysDeptBody) => client.put<unknown>('/system/dept', { body }),
      /** **单个 id**，不是 ids 列表 —— 与 role/post/config 的批量删除不同形。 */
      remove: (deptId: string) => client.del<unknown>(`/system/dept/${encodeURIComponent(deptId)}`),
      options: () => client.get<SysDeptVo[]>('/system/dept/optionselect'),
      deptTree: () => client.get<unknown>('/system/user/deptTree'),
    },

    posts: {
      list: (query: SysPostQuery) => client.getRows<SysPostVo>('/system/post/list', { query: { ...query } }),
      get: (postId: string) => client.get<SysPostVo>(`/system/post/${encodeURIComponent(postId)}`),
      create: (body: SysPostBody) => client.post<unknown>('/system/post', { body }),
      update: (body: SysPostBody) => client.put<unknown>('/system/post', { body }),
      remove: (postIds: readonly string[]) =>
        client.del<unknown>(`/system/post/${postIds.map(encodeURIComponent).join(',')}`),
      options: () => client.get<SysPostVo[]>('/system/post/optionselect'),
      deptTree: () => client.get<unknown>('/system/post/deptTree'),
      exportUrl: () => '/system/post/export',
    },

    dictTypes: {
      list: (query: SysDictTypeQuery) => client.getRows<SysDictTypeVo>('/system/dict/type/list', { query: { ...query } }),
      get: (dictId: string) => client.get<SysDictTypeVo>(`/system/dict/type/${encodeURIComponent(dictId)}`),
      create: (body: SysDictTypeBody) => client.post<unknown>('/system/dict/type', { body }),
      update: (body: SysDictTypeBody) => client.put<unknown>('/system/dict/type', { body }),
      remove: (dictIds: readonly string[]) =>
        client.del<unknown>(`/system/dict/type/${dictIds.map(encodeURIComponent).join(',')}`),
      options: () => client.get<SysDictTypeVo[]>('/system/dict/type/optionselect'),
      /** 刷新字典缓存：**DELETE** 动词（不是 POST），且**无参数**。 */
      refreshCache: () => client.del<unknown>('/system/dict/type/refreshCache'),
      exportUrl: () => '/system/dict/type/export',
    },

    dictData: {
      list: (query: SysDictDataQuery) => client.getRows<SysDictDataVo>('/system/dict/data/list', { query: { ...query } }),
      get: (dictCode: string) => client.get<SysDictDataVo>(`/system/dict/data/${encodeURIComponent(dictCode)}`),
      create: (body: SysDictDataBody) => client.post<unknown>('/system/dict/data', { body }),
      update: (body: SysDictDataBody) => client.put<unknown>('/system/dict/data', { body }),
      /** 删除用的是 **dictCode**，不是 dictId —— 传错字段会静默删错/不删。 */
      remove: (dictCodes: readonly string[]) =>
        client.del<unknown>(`/system/dict/data/${dictCodes.map(encodeURIComponent).join(',')}`),
      /** 按字典类型取全部数据（**不分页**，给下拉框用）。 */
      byType: (dictType: string) => client.get<SysDictDataVo[]>(`/system/dict/data/type/${encodeURIComponent(dictType)}`),
      exportUrl: () => '/system/dict/data/export',
    },

    configs: {
      list: (query: SysConfigQuery) => client.getRows<SysConfigVo>('/system/config/list', { query: { ...query } }),
      get: (configId: string) => client.get<SysConfigVo>(`/system/config/${encodeURIComponent(configId)}`),
      /** 这个端点**没有 `@SaCheckPermission`**：任何已登录用户可读。前端不因此放宽 UI，只如实登记。 */
      byKey: (configKey: string) => client.get<string>(`/system/config/configKey/${encodeURIComponent(configKey)}`),
      create: (body: SysConfigBody) => client.post<unknown>('/system/config', { body }),
      update: (body: SysConfigBody) => client.put<unknown>('/system/config', { body }),
      /** 按键更新：**PUT**，body 与创建同形。 */
      updateByKey: (body: SysConfigBody) => client.put<unknown>('/system/config/updateByKey', { body }),
      remove: (configIds: readonly string[]) =>
        client.del<unknown>(`/system/config/${configIds.map(encodeURIComponent).join(',')}`),
      /** 刷新参数缓存：**DELETE** 且**无参数**。 */
      refreshCache: () => client.del<unknown>('/system/config/refreshCache'),
      exportUrl: () => '/system/config/export',
    },

    notices: {
      list: (query: SysNoticeQuery) => client.getRows<SysNoticeVo>('/system/notice/list', { query: { ...query } }),
      get: (noticeId: string) => client.get<SysNoticeVo>(`/system/notice/${encodeURIComponent(noticeId)}`),
      create: (body: SysNoticeBody) => client.post<unknown>('/system/notice', { body }),
      update: (body: SysNoticeBody) => client.put<unknown>('/system/notice', { body }),
      remove: (noticeIds: readonly string[]) =>
        client.del<unknown>(`/system/notice/${noticeIds.map(encodeURIComponent).join(',')}`),
      /** 后端**没有**公告导出端点：`POST /system/notice/export` 未注册（扫描 589 注解中不存在）。 */
    },

    clients: {
      list: (query: SysClientQuery) => client.getRows<SysClientVo>('/system/client/list', { query: { ...query } }),
      get: (id: string) => client.get<SysClientVo>(`/system/client/${encodeURIComponent(id)}`),
      create: (body: SysClientBody) => client.post<unknown>('/system/client', { body }),
      update: (body: SysClientBody) => client.put<unknown>('/system/client', { body }),
      changeStatus: (id: string, status: PlatformStatus) =>
        client.put<unknown>('/system/client/changeStatus', { body: { id, status } }),
      remove: (ids: readonly string[]) => client.del<unknown>(`/system/client/${ids.map(encodeURIComponent).join(',')}`),
      exportUrl: () => '/system/client/export',
    },

    /**
     * OSS 对象存储（F01 op11，RW-15 补）。`SysOssController`（`/resource/oss`）：
     * `GET /list`（`system:oss:list`）、`GET /listByIds/{ossIds}`（`system:oss:query`）、
     * `DELETE /{ossIds}`（`system:oss:remove`）。
     * ⚠️ `POST /upload|/fileUpload`（multipart）与 `GET /download/{ossId}`（二进制流）
     * 依赖 multipart / blob 传输，共享 `PlatformClient` 只处理 JSON ⇒ 不在本模块提供
     * （前端缺口已登记，见 RW-15 报告 §NOT_RUN）。
     */
    oss: {
      list: (query: SysOssQuery) => client.getRows<SysOssVo>('/resource/oss/list', { query: { ...query } }),
      listByIds: (ossIds: readonly string[]) =>
        client.get<SysOssVo[]>(`/resource/oss/listByIds/${ossIds.map(encodeURIComponent).join(',')}`),
      remove: (ossIds: readonly string[]) =>
        client.del<unknown>(`/resource/oss/${ossIds.map(encodeURIComponent).join(',')}`),
      /** 下载是二进制响应（后端直接写 `HttpServletResponse`）——见上方说明。 */
      downloadPath: (ossId: string) => `/resource/oss/download/${encodeURIComponent(ossId)}`,
    },

    /** OSS 配置（`SysOssConfigController`，`/resource/oss/config`）。 */
    ossConfigs: {
      list: (query: SysOssConfigQuery) =>
        client.getRows<SysOssConfigVo>('/resource/oss/config/list', { query: { ...query } }),
      get: (ossConfigId: string) =>
        client.get<SysOssConfigVo>(`/resource/oss/config/${encodeURIComponent(ossConfigId)}`),
      create: (body: SysOssConfigBody) => client.post<unknown>('/resource/oss/config', { body }),
      update: (body: SysOssConfigBody) => client.put<unknown>('/resource/oss/config', { body }),
      remove: (ids: readonly string[]) =>
        client.del<unknown>(`/resource/oss/config/${ids.map(encodeURIComponent).join(',')}`),
      changeStatus: (ossConfigId: string, status: PlatformStatus) =>
        client.put<unknown>('/resource/oss/config/changeStatus', { body: { ossConfigId, status } }),
    },

    /**
     * 个人中心（F01 op13，RW-15 补）。`SysProfileController`（`/system/user/profile`）：
     * `GET`（无 `@SaCheckPermission`，只读本人）、`PUT`（改资料）、
     * `PUT /updatePwd`（body `{oldPassword,newPassword}`）。
     * ⚠️ `POST /avatar` 是 multipart ⇒ 不在本模块提供（缺口登记）。
     */
    profile: {
      get: () => client.get<SysProfileVo>('/system/user/profile'),
      update: (body: SysProfileBody) => client.put<unknown>('/system/user/profile', { body }),
      updatePwd: (body: { oldPassword: string; newPassword: string }) =>
        client.put<unknown>('/system/user/profile/updatePwd', { body }),
    },

    /** 社交关系（`SysSocialController`，`GET /system/social/list`，**无** `@SaCheckPermission`）。 */
    socials: {
      list: () => client.get<SysSocialVo[]>('/system/social/list'),
    },

    /**
     * 短链（F01 op14，RW-15 补）。`SysUrlController`（`/system/url`）：
     * `list`（`system:url:list`）、`shortcuts`（`system:url:list` 或 `coding:harness:use`，
     * `SaMode.OR`）、`{urlId}`（`system:url:query`）、`POST`（`system:url:add`）、
     * `PUT`（`system:url:edit`）、`DELETE`（`system:url:remove`）。
     * ⚠️ 服务端**没有** `export` 端点。
     */
    urls: {
      list: (query: SysUrlQuery) => client.getRows<SysUrlVo>('/system/url/list', { query: { ...query } }),
      shortcuts: () => client.get<SysUrlShortcutVo[]>('/system/url/shortcuts'),
      get: (urlId: string) => client.get<SysUrlVo>(`/system/url/${encodeURIComponent(urlId)}`),
      create: (body: SysUrlBody) => client.post<unknown>('/system/url', { body }),
      update: (body: SysUrlBody) => client.put<unknown>('/system/url', { body }),
      remove: (urlIds: readonly string[]) => client.del<unknown>(`/system/url/${urlIds.map(encodeURIComponent).join(',')}`),
    },
  };
}

export type SystemApi = ReturnType<typeof createSystemApi>;
