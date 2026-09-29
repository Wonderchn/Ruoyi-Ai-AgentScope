# Ruoyi-Ai-AgentScope

面向多租户的 AI 平台工程。`services/platform` 来自 ruoyi-ai，承载业务平台；`services/ai` 来自 ragent，承载 RAG 与 Agent；`services/ruoyi-web` 是若依用户前端的固定源码快照，供后续统一入口设计使用。当前提交是工程化基线，**两服务和前端尚未完成统一身份、租户隔离和业务接入，不能用于客户生产环境**。

## 目录和构建

- `services/platform`：独立 Maven 构建根，Java 17，Spring Boot 3。
- `services/ai`：独立 Maven 构建根，Java 17，Spring Boot 4；`frontend` 是原 ragent React 前端，供后续迁移参考。
- `services/ruoyi-web`：若依用户前端独立构建根，Vue 3、Node.js >=22.13.0、pnpm 11.0.9；当前仅为上游源码快照，未接入 platform 或 ai。构建前按 `.env.example` 在本机提供环境配置，勿提交真实值。
- `infra/docker`：分别构建 platform 和 ai 的镜像。
- `.github/workflows/ci.yml`：PR 验证；`release.yml`：main 验证通过后向 GHCR 发布镜像，生成绑定两份镜像 digest 的 release manifest，再在无注册表凭据的干净 runner 上按 digest 拉取并核对来源提交。
- `scripts/agent`：本机无头 Codex 执行和 Draft PR 发布。

本地预备 Java 17、Maven、Node.js、npm；构建分别运行：

```text
cd services/platform && mvn -B -ntp -Pdev clean verify
cd services/ai && mvn -B -ntp -Pci clean verify
cd services/ai/frontend && npm ci && npm run lint && npm run build
cd services/ruoyi-web && pnpm install --frozen-lockfile && pnpm build
```

某些原上游测试需要外部组件或模型服务；CI 专用范围和外部测试清单见 `docs/runbooks/external-tests.md`。应用配置所需环境变量见 `docs/configuration.md`，不得在仓库中提交真实凭据。

## 无头开发

本机任务使用 `.agent/tasks/<task-id>.json`，按 `.agent/task.schema.json` 校验。批准范围、基线 SHA、允许路径和验收命令均记录在任务文件；运行报告按 `.agent/run-result.schema.json` 校验，验证通过再发布 Draft PR。默认入口在仓库旁创建每任务独立 Git worktree，原来的 `main` 检出保持不动；自动修改只进入任务分支，不能直接合并或部署。

```powershell
pwsh -File scripts/agent/Start-IsolatedTask.ps1 -TaskFile .agent/tasks/example.json -RepositoryRoot .
# 以下命令在输出的任务 worktree 内执行
pwsh -File scripts/agent/Publish-AgentPr.ps1 -TaskFile .agent/tasks/example.json -RepositoryRoot . -ResultFile .agent/runs/example/result.json -Draft
```

本机需先完成 `codex login` 与 GitHub CLI `gh auth login`。任务文件的 `approved` 字段只是记录；发布脚本还会检查批准人、Spec hash 与允许路径。首期由操作者创建并审核任务文件，任何来自 issue、PR 或模型输出的文本都不能自行授权。

通过验证的运行结果包含已暂存补丁的 SHA-256。发布时会再次核对补丁、允许路径和凭据模式；提交带有任务与结果摘要。若推送或创建 PR 中断，可用同一任务、Spec 和结果文件重跑发布脚本：仅当本地提交与验证结果一致、远端分支没有分歧时继续，并复用已有 PR。已关闭或合并的 PR 不会被重复创建。

执行包装器要求检出中除批准任务文件、Spec 和当前运行日志外没有其他 Git 忽略文件，并拒绝代理新建的忽略文件以及变更路径中的符号链接、Windows junction；构建产物由包装器在代理退出后运行验证时生成。任务运行日志目录中的输出也会检查重解析点，避免代理把报告写入重定向路径。

## 上游与许可

具体来源 SHA 见 `docs/upstreams.lock.json`，保留 `services/platform/LICENSE`、`services/ai/LICENSE`、`services/ruoyi-web/license` 及相关 NOTICE。`ruoyi-web` 原跟踪的 `.env.development`、`.env.production` 以占位 `.env.example` 替代，避免把上游环境值直接带入本项目。未来从上游升级时，固定新旧 SHA，并独立提交升级 PR。许可证和第三方依赖使用前需核对具体分发范围。
