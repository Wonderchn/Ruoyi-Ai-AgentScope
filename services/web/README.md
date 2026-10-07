# services/web — 前端工作区（E4 骨架）

platform 内嵌 AgentScope 迁移的**唯一前端构建根**。原 `services/ruoyi-web`（若依用户前端
固定快照）挂进来成为 `apps/workbench`，AI 协议资产抽成 `packages/events`，两个应用（后续
还有管理端）共用一套线上契约。

```text
services/web/
  package.json           工作区根（private；scripts: test/typecheck/lint/build/dev）
  pnpm-workspace.yaml    packages: apps/*, packages/*
  pnpm-lock.yaml         唯一锁文件（工作区根；不要在各 package 内保留锁文件）
  apps/workbench/        原 services/ruoyi-web（Vue 3 + Vite + Element Plus）
  packages/events/       @ruoyi/events —— AI 协议层（SSE 语法、运行事件流、RAG 传输与客户端）
```

## 为什么抽 `packages/events`

协议资产原本散在应用里，一旦管理端（E5）也要用同一套 SSE/run 契约，就会出现两份实现并
逐渐漂移。抽包后：

- `@ruoyi/events/sse`：`SseSyntax`（帧解析：注释、多行 data、id）、`RunStreamClient`
  （连续 seq、游标重放、410 快照回退、逐帧身份复核）；
- `@ruoyi/events/rag`：`logic`（纯请求/响应整形与引用工具）、`transport`（身份域内的信封
  处理：迟到的响应不能影响新身份）、`createRagApi(deps)`（P2 端点全集，身份来源**注入**）。

应用侧只保留绑定：`apps/workbench/src/api/rag/index.ts` 用 Pinia user store 构造
`createRagApi`，并把绑定后的函数按原名导出，页面无需改动；`src/utils/sse/*` 变成
`export * from '@ruoyi/events/sse'` 的转发文件，保持既有 import 路径可用。

## 命令

```bash
cd services/web
pnpm install            # 工作区根安装（唯一锁文件）
pnpm test               # node:test 协议测试（@ruoyi/events）
pnpm typecheck          # 协议包 tsc + 应用 vue-tsc -b
pnpm lint               # 应用 ESLint
pnpm build              # 应用生产构建（vue-tsc -b && vite build）
pnpm dev                # 起 workbench 开发服务器
```

单包操作：`pnpm --filter @ruoyi/events test`、`pnpm --filter @ruoyi/workbench build`。

## 环境

- Node >= 22.13.0，pnpm 11.0.9（根 `packageManager` 固定）。
- 应用配置按 `apps/workbench/.env.example` 在本机提供，勿提交真实值。
- `allowBuilds` 在根 `pnpm-workspace.yaml`：`esbuild`/`vue-demi` 的构建脚本保持关闭
  （与迁移前一致）。

## 现状与未完成

- 已完成：工作区建立、应用迁入 `apps/workbench`、协议资产抽包 + 转发、node:test 协议测试
  随包迁移、CI/Docker/docs 路径更新。
- **未完成（E4 剩余）**：F03/F04/F05/F10/F15/F23 核心切片（真实数据/权限/异常闭环）、
  应用级组件测试、管理端应用（E5）、`RagApi` 里与具体应用无关的其余端点若出现第二处消费
  再评估是否需要进一步下沉。
