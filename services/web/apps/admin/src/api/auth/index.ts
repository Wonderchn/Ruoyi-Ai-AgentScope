/**
 * 平台登录 / 身份 API。
 *
 * 端点与后端对应（已实测）：
 * - `POST /auth/login`        → `R<LoginVo>`（`LoginVo`：`access_token`/`client_id`/`expire_in`/`scope`，snake_case）
 * - `POST /auth/logout`       → `R<Void>`
 * - `GET  /system/user/getInfo` → `R<UserInfoVo>`（`user` + `permissions` + `roles`）
 *
 * 两处必须注意的后端事实（都在规格里登记）：
 * 1. `AuthController.login` 标了 `@ApiEncrypt`。该注解由 `CryptoFilter` 实现，而
 *    `CryptoFilter` 的门控是 `api-decrypt.enabled=true`，**当前配置是 `false`**
 *    （`application.yml` 第 234 行）。也就是说现在登录体是明文 JSON；
 *    一旦部署侧把开关打开，前端必须同步加密，否则登录全部失败。
 *    这里**不实现加密**，而是把这条依赖显式登记（见规格「明确未做」），
 *    避免"看起来支持加密、实际格式不对"的假实现。
 * 2. `GET /system/user/getInfo` 无 `@SaCheckPermission`，但返回的 `permissions`
 *    就是 `LoginHelper.getLoginUser().getMenuPermission()`——超管在这里是单个 `*:*:*`。
 *    前端的按钮显示判定消费它；**拒绝仍由后端每个端点的注解负责**。
 */
import platformClient from '@/utils/request';

export interface LoginDTO {
  username: string;
  password: string;
  clientId?: string;
  grantType?: string;
  tenantId?: string;
  code?: string;
  uuid?: string;
}

/**
 * 平台 `LoginVo` 的 **wire 形状**：与后端 `org.ruoyi.system.domain.vo.LoginVo`
 * 的 `@JsonProperty` 逐字段一致（snake_case）。`platform-client.unwrapData`
 * 原样返回 `data`，不做驼峰转换，所以这里必须写传输层真实的字段名。
 */
export interface LoginVo {
  access_token?: string;
  refresh_token?: string;
  expire_in?: number;
  refresh_expire_in?: number;
  client_id?: string;
  scope?: string;
  openid?: string;
}

/** `UserInfoVo.user`（管理端用到的字段）。 */
export interface PlatformUser {
  userId?: string;
  tenantId?: string;
  userName?: string;
  nickName?: string;
  deptName?: string;
  email?: string;
  phonenumber?: string;
  status?: string;
  avatar?: string;
}

/** `GET /system/user/getInfo` 的响应。 */
export interface UserInfoVo {
  user?: PlatformUser;
  /** 菜单权限集合；超管是单个 `*:*:*` */
  permissions?: string[];
  roles?: string[];
}

export interface LoginTenant {
  tenantId: string;
  companyName: string;
}

export interface LoginTenantsVo {
  tenantEnabled?: boolean;
  voList?: LoginTenant[];
}

/** 公开租户列表（无需登录），用于登录页选择租户而不是手填租户 ID。 */
export function getLoginTenants() {
  return platformClient.get<LoginTenantsVo>('/auth/tenant/list');
}

export function login(data: LoginDTO) {
  return platformClient.post<LoginVo>('/auth/login', { body: data });
}

export function logout() {
  return platformClient.post<void>('/auth/logout');
}

export function getUserInfo() {
  return platformClient.get<UserInfoVo>('/system/user/getInfo');
}
