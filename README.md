# Ruoyi-Ai-AgentScope

面向多租户的 AI 平台工程。`services/platform` 来自 ruoyi-ai，承载业务平台；`services/ai` 来自 ragent，承载 RAG 与 Agent。当前提交是工程化基线，**两服务尚未完成统一身份、租户隔离和业务接入，不能用于客户生产环境**。

## 目录和构建

- `services/platform`：独立 Maven 构建根，Java 17，Spring Boot 3。
- `services/ai`：独立 Maven 构建根，Java 17，Spring Boot 4；`frontend` 是原 ragent React 前端，供后续迁移参考。
- `infra/docker`：分别构建 platform 和 ai 的镜像。
- `.github/workflows/ci.yml`：PR 验证；`release.yml`：main 验证通过后向 GHCR 发布镜像。
- `scripts/agent`：本机无头 Codex 执行和 Draft PR 发布。

本地预备 Java 17、Maven、Node.js、npm；构建分别运行：

```text
cd services/platform && mvn -B -ntp -Pdev clean verify
cd services/ai && mvn -B -ntp -Pci clean verify
cd services/ai/frontend && npm ci && npm run lint && npm run build
```

某些原上游测试需要外部组件或模型服务；CI 专用范围和外部测试清单见 `docs/runbooks/external-tests.md`。应用配置所需环境变量见 `docs/configuration.md`，不得在仓库中提交真实凭据。

## 无头开发

本机任务使用 `.agent/tasks/<task-id>.json`，按 `.agent/task.schema.json` 校验。批准范围、基线 SHA、允许路径和验收命令均记录在任务文件；运行报告按 `.agent/run-result.schema.json` 校验，验证通过再发布 Draft PR。自动修改只进入任务分支，不能直接合并或部署。

```powershell
pwsh -File scripts/agent/Invoke-AgentTask.ps1 -TaskFile .agent/tasks/example.json -RepositoryRoot .
pwsh -File scripts/agent/Publish-AgentPr.ps1 -TaskFile .agent/tasks/example.json -RepositoryRoot . -ResultFile .agent/runs/example/result.json -Draft
```

本机需先完成 `codex login` 与 GitHub CLI `gh auth login`。任务文件的 `approved` 字段只是记录；发布脚本还会检查批准人、Spec hash 与允许路径。首期由操作者创建并审核任务文件，任何来自 issue、PR 或模型输出的文本都不能自行授权。

## 上游与许可

具体来源 SHA 见 `docs/upstreams.lock.json`，保留 `services/platform/LICENSE`、`services/ai/LICENSE` 及相关 NOTICE。未来从上游升级时，固定新旧 SHA，并独立提交升级 PR。许可证和第三方依赖使用前需核对具体分发范围。
