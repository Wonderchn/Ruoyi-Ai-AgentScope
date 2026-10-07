/**
 * 平台用户管理 API 工厂（F01 op3）。
 *
 * 端点逐条对应 `SysUserController`（`/system/user`，RW-14 实查）：
 * `GET /list`、`GET /list/dept/{deptId}`、`GET /deptTree`（均 system:user:list）、
 * `GET /{userId}`（query）、`POST /`（add）、`PUT /`（edit）、
 * `PUT /changeStatus`（body `{userId,status}`）、`PUT /resetPwd`（body `{userId,password}`，
 * 服务端 BCrypt 后落库）、`DELETE /{userIds}`（remove）、`GET /optionselect`（query）、
 * `GET /authRole/{userId}`（query）、`PUT /authRole`（**query 参数** `userId` + 重复 `roleIds`）。
 *
 * ⚠️ 缺口（不在本模块提供）：`POST /export`（Excel 写响应流）、
 * `POST /importData` / `POST /importTemplate`（multipart）。
 * ⚠️ `userId` 是雪花 Long，**按 string 处理**（G-46）。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { PageParams } from '@/utils';

export interface SysUserVo {
  userId?: string;
  tenantId?: string;
  deptId?: string;
  userName?: string;
  nickName?: string;
  userType?: string;
  email?: string;
  phonenumber?: string;
  sex?: string;
  status?: string;
  loginIp?: string;
  loginDate?: string;
  createTime?: string;
  deptName?: string;
  remark?: string;
}

export interface SysUserQuery extends PageParams {
  userName?: string;
  phonenumber?: string;
  status?: string;
  deptId?: string;
}

export interface UserAuthRoleVo {
  user?: SysUserVo;
  roles?: Array<{ roleId?: string; roleName?: string; roleKey?: string; flag?: boolean }>;
  roleIds?: string[];
}

/** 逐字权限串（后端 `@SaCheckPermission`）。 */
export const USER_PERMISSIONS = {
  list: 'system:user:list',
  query: 'system:user:query',
  add: 'system:user:add',
  edit: 'system:user:edit',
  remove: 'system:user:remove',
  resetPwd: 'system:user:resetPwd',
  export: 'system:user:export',
  import: 'system:user:import',
} as const;

export function createUserApi(client: PlatformClient) {
  return {
    list: (query: SysUserQuery) =>
      client.getRows<SysUserVo>('/system/user/list', {
        query: {
          pageNum: query.pageNum,
          pageSize: query.pageSize,
          userName: query.userName,
          phonenumber: query.phonenumber,
          status: query.status,
          deptId: query.deptId,
        },
      }),
    listByDept: (deptId: string, query: Omit<SysUserQuery, 'deptId'> = { pageNum: 1, pageSize: 10 }) =>
      client.getRows<SysUserVo>(`/system/user/list/dept/${encodeURIComponent(deptId)}`, {
        query: { pageNum: query.pageNum, pageSize: query.pageSize, userName: query.userName, status: query.status },
      }),
    get: (userId: string) => client.get<SysUserVo>(`/system/user/${encodeURIComponent(userId)}`),
    create: (body: Record<string, unknown>) => client.post<unknown>('/system/user', { body }),
    update: (body: Record<string, unknown>) => client.put<unknown>('/system/user', { body }),
    changeStatus: (userId: string, status: string) =>
      client.put<unknown>('/system/user/changeStatus', { body: { userId, status } }),
    /** 重置口令：body `{userId,password}`（服务端 BCrypt）。 */
    resetPwd: (userId: string, password: string) =>
      client.put<unknown>('/system/user/resetPwd', { body: { userId, password } }),
    remove: (userIds: readonly string[]) =>
      client.del<unknown>(`/system/user/${userIds.map(encodeURIComponent).join(',')}`),
    options: () => client.get<SysUserVo[]>('/system/user/optionselect'),
    deptTree: (query: { deptId?: string; deptName?: string } = {}) =>
      client.get<unknown>('/system/user/deptTree', { query: { ...query } }),
    authRole: (userId: string) =>
      client.get<UserAuthRoleVo>(`/system/user/authRole/${encodeURIComponent(userId)}`),
    /**
     * 保存用户角色：服务端签名是 `(Long userId, Long[] roleIds)` ⇒ **query 参数**
     * （重复 key `roleIds`）。`PlatformClient` 的 query 只接受标量，故手工拼串。
     */
    saveAuthRole: (userId: string, roleIds: readonly string[]) => {
      const params = new URLSearchParams();
      params.append('userId', userId);
      for (const roleId of roleIds)
        params.append('roleIds', roleId);
      return client.put<unknown>(`/system/user/authRole?${params.toString()}`);
    },
  };
}

export type UserApi = ReturnType<typeof createUserApi>;
