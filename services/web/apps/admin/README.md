# apps/admin — 平台管理端（E5 / WP-034 前端半）

Vue 3 + TypeScript + Vite + Element Plus 的**平台管理端**，与 `apps/workbench`（AI 工作台）
同处 `services/web` workspace，共用一套身份、权限与请求语义。

## 为什么存在

`services/web/README.md` 的"现状与未完成"里写着"管理端应用（E5）"未完成。
本应用是那一步的骨架：**不是空壳**——布局、路由、真实调用平台 API 的页面都已接线。

## 与 workbench 的分工

| 关注点 | 归属 |
| --- | --- |
| SSE 帧语法、run 流恢复、RAG 传输 | `@ruoyi/events`（两个应用共用） |
| token/clientId、401-403 语义、权限匹配、JSON 客户端 | `@ruoyi/platform-client`（两个应用共用） |
| AI 会话与聊天页面 | `apps/workbench` |
| 租户/用户/菜单与权限管理页面 | `apps/admin` |

管理端**不复制** workbench 的恢复与订阅实现：它根本不订阅 run 流；
需要时直接 `import` `@ruoyi/events`。

## 命令

```bash
cd services/web
pnpm --filter @ruoyi/admin dev        # 开发服务器
pnpm --filter @ruoyi/admin test       # node:test（纯逻辑，无浏览器）
pnpm --filter @ruoyi/admin typecheck  # vue-tsc -b
pnpm --filter @ruoyi/admin lint       # eslint
pnpm --filter @ruoyi/admin build      # vue-tsc -b && vite build
```

## 目录

```text
src/
├── api/            平台 API 绑定（auth/menu/tenant/user），端点与后端控制器逐条对应
├── composables/    useAuthSession（登录/身份/菜单）、usePermission（权限判定薄绑定）
├── config/         首页、路由白名单、权限串常量
├── layouts/        侧栏 + 顶栏 + 内容区（真实布局，非占位）
├── pages/          dashboard / tenant / user / menu / login / error
├── routers/        静态路由 + 身份守卫
├── stores/         identity（pinia；身份持久化，权限不持久化）
├── styles/         全局样式
└── utils/          menu（菜单树转换）、list（分页与加载纪元）、request（客户端绑定）
```

## 权限怎么工作（重要）

**前端的权限判断只决定"显示不显示"，不是授权。**

- 权限集合来自后端 `GET /system/user/getInfo` 的 `permissions`（`LoginHelper.getMenuPermission()`）——
  前端**不推断**权限，只消费；超管在那里就是单个 `*:*:*`。
- `@ruoyi/platform-client` 提供两种语义，**不能混用**：
  - `hasPermission` → 平台菜单通配语义（`*:*:*` 命中一切），用于菜单/按钮显示；
  - `hasExactPermission` → 精确字符串相等，用于 AI 资源动作
    （后端 `AiActionRegistry` 明确"超管的 `*:*:*` 不得产生 AI 资源 ACL 豁免"）。
- 真正的拒绝在每个后端端点的 `@SaCheckPermission` 与 AI 网关的 `AiActionRegistry`。
  手动敲 URL 绕过前端隐藏，后端一样拒绝。

## 环境变量

按 `.env.example` 在本机提供 `.env.development`，勿提交真实值：

- `VITE_API_URL`（如 `/api`）
- `VITE_CLIENT_ID`（公开客户端 id；后端 `sys_client` 表里必须存在且 grantType 含 `password`）

## 现状与未完成

见 `mydocs/platform-embedded/specs/E5-wp034-admin-web-baseline.md` 的「明确未做」一节。
简言之：登录链路**未对真实后端做过端到端验证**（本机无 PG/Redis/运行中的平台服务），
组件级测试需要新增依赖（未做），`@ApiEncrypt` 开启时的请求加密未实现。
