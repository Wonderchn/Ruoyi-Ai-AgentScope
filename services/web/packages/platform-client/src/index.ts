/**
 * @ruoyi/platform-client — 平台身份、权限与 HTTP 客户端的**唯一**共享实现。
 *
 * 抽包理由（与 `@ruoyi/events` 的抽包理由同源，见 `services/web/README.md`）：
 * 管理端（E5）与工作台都要"带 token/clientId 调平台 API、按 `sys_menu` 权限显示按钮、
 * 401 登出 403 跳转"。这三件事各写一份的下场是：管理端 401 清了 `tenantId`、
 * 工作台忘了清，或者一边按通配匹配权限、另一边按精确匹配——都只在生产上才暴露。
 *
 * 与 `@ruoyi/events` 的分工：
 * - `@ruoyi/events`：**线上协议**（SSE 帧语法、run 流恢复、RAG 传输）；
 * - `@ruoyi/platform-client`：**平台身份与授权**（token/clientId/401-403、权限匹配、
 *   普通 JSON 客户端）。
 * 两者都不依赖 Vue/pinia，都可被 `node:test` 直接覆盖。
 */
export * from './identity/index.ts';
export * from './permission/index.ts';
export * from './permission/actions.ts';
export * from './http/index.ts';
