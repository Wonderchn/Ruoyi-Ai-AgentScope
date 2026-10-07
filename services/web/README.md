# services/web — 双前端工作区

platform 内嵌 AI 主线的统一前端构建根。两个 Vue 3 应用共享身份/请求层和 run/SSE/RAG 契约，统一安装、测试和构建。

| 路径 | 用途 |
| --- | --- |
| `apps/workbench` | 用户工作台；镜像部署在 `/` |
| `apps/admin` | 租户、用户、角色、菜单和 AI 管理端；镜像部署在 `/admin/` |
| `packages/events` | `@ruoyi/events`：SSE 解析、游标重放、run/RAG 客户端与身份域传输 |
| `packages/platform-client` | `@ruoyi/platform-client`：身份、权限、平台信封、超时和取消处理 |

需要 Node.js >=22.13.0、pnpm 11.0.9。仅根 `pnpm-lock.yaml` 为安装依据。

```bash
cd services/web
pnpm install --frozen-lockfile
pnpm test
pnpm typecheck
pnpm lint
pnpm build
pnpm dev                         # 工作台开发服务器
pnpm --filter @ruoyi/admin dev    # 管理端开发服务器
```

单包命令可用 `pnpm --filter @ruoyi/events test` 等。测试包含协议、身份隔离、权限配对、页面数据与取消/超时处理；CI 同时执行全部工作区验证。

管理端开发默认基路径 `/`；构建时设置 `WEB_ADMIN_BASE_PATH=/admin/` 可部署到子路径，Vue Router 使用构建出的 `BASE_URL`。Docker 已设置该变量，同时复制两个应用的 dist；不要只构建工作台或漏复制共享包的 package.json。

配置使用应用内 `.env.example`，真实值不得提交。`VITE_CLIENT_ID` 是公开登录客户端标识，应匹配平台客户端配置。Docker 构建排除本地 `.env`、node_modules 和 dist。依赖构建脚本许可见根 `pnpm-workspace.yaml`。

`infra/dev/nginx.conf` 为双 SPA 提供深链接回退；只代理平台公开 `/api/`、`/auth/`、`/system/`、`/monitor/`，SSE 不缓冲，私有对象经授权网关交付。`/internal/` 与 `/actuator` 拒绝访问。API 不通过 `admin` 页面基路径寻址。

管理页存在不代表所有后端能力均已迁移。旧模型/MCP/设置接口和 Skills 的范围限制见根 README；后端权限与平台管理身份是最终授权依据。发布容器的资源和代理检查使用合成上游，不代替目标环境的业务验收。
