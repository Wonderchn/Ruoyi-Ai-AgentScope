/**
 * 平台用户管理 API。
 *
 * 端点与后端 `org.ruoyi.system.controller.system.SysUserController` 对应（已实测）：
 * - `GET /system/user/list`  → `TableDataInfo<SysUserVo>`（需 `system:user:list`）
 *
 * `SysUserVo` 里 `userId` 是 `Long`（雪花 ID，19 位，超过 JS 安全整数 2^53），
 * 因此**前端类型声明为 string**：后端用 Jackson 的 Long→String 序列化配置输出时
 * 才是安全的；若某处漏配，JSON.parse 会先把精度丢掉，任何前端补救都来不及。
 * 这条在规格里登记为需要后端保证的前提。
 */
import type { PageParams } from '@/utils';
import platformClient from '@/utils/request';

/** 平台 `SysUserVo`（只声明管理端展示用到的字段）。 */
export interface SysUserVo {
  /** 雪花 ID：**字符串**（见本文件头注释） */
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
}

export interface SysUserQuery extends PageParams {
  userName?: string;
  phonenumber?: string;
  status?: string;
  deptId?: string;
}

export function listUsers(query: SysUserQuery) {
  return platformClient.getRows<SysUserVo>('/system/user/list', {
    query: {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      userName: query.userName,
      phonenumber: query.phonenumber,
      status: query.status,
      deptId: query.deptId,
    },
  });
}
