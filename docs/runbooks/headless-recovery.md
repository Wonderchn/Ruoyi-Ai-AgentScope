# 本地 headless 任务与 PR 检查恢复

适用于超时、进程崩溃、机器重启、验证失败和推送中断。以下命令从仓库根目录用 PowerShell 执行；将 `<taskId>` 等占位符替换为已批准任务的值。当前脚本**没有自动续跑功能**，不要跨任务使用 `codex resume --last`。

## 1. 先确认任务与中断阶段

确认旧进程已经退出，避免两个执行器同时写入。保留当前差异、未跟踪文件和 `.agent/runs/<taskId>/` 的本地副本；日志、任务历史和客户数据不得提交到公开仓库。

```powershell
$taskFile = '.agent/tasks/<taskId>.json'
$task = Get-Content -LiteralPath $taskFile -Raw | ConvertFrom-Json
Get-FileHash -LiteralPath $taskFile -Algorithm SHA256
Get-FileHash -LiteralPath $task.specPath -Algorithm SHA256
git branch --show-current
git rev-parse HEAD
git status --short
```

- 核对任务 ID、`approval`、`allowedPaths`、`validation` 和批准记录；Spec 的 SHA-256 必须等于 `specSha256`。有 `result.json` 时，当前任务文件哈希还必须等于其中的 `taskSha256`，Spec 哈希必须等于其中的 `specSha256`。
- 所有恢复改动留在 `agent/<taskId>`，不得修改 `main`。执行器启动要求干净工作区且 `HEAD == task.baseSha`；结果中的 `baseSha` 也应一致。发布已产生提交时，记录本地及远端提交，检查 `git log --oneline <baseSha>..HEAD` 与 `git merge-base --is-ancestor <baseSha> HEAD`，不要为通过检查而改写基线或结果。
- 检查运行目录的 `prompt.md`、`codex.jsonl`、`codex.stderr.log`、`agent-final.json` 和 `result.json`。以结果中的 `status`、`reason`、`codexExitCode`、`changedPaths`、`validationPassed` 判断阶段。`agent-final.json` 是模型答复，不是验证通过证明。
- 包装器在子进程结束后才写 Codex 输出日志；包装器崩溃或机器重启可能留下缺失、不完整或旧的文件。核对时间与本次执行；缺少结果不能视为成功，验证的终端输出也不保证保存在 Codex 日志里。

## 2. 恢复本地执行与验证

`Invoke-AgentTask.ps1` 遇到已有任务分支、脏工作区或不匹配的 HEAD 会拒绝启动；它不能直接接着旧运行执行。先在原任务分支审查并保留已完成工作。需要重新执行时，由维护者准备位于批准 `baseSha` 的独立、干净检出，其中尚无同名本地任务分支，并确保相同任务、Spec 及所需工具可用，再启动：

```powershell
pwsh -NoProfile -File scripts/agent/Invoke-AgentTask.ps1 `
  -TaskFile $taskFile -RepositoryRoot . -TimeoutMinutes 30
```

这是从批准任务重新执行，不是自动恢复；不要删除旧分支或丢弃旧差异来绕过保护。只有确认旧进程退出后才启动新执行。单独重跑验证可用于诊断，但不会更新或生成可信的通过结果；缺失或失败的结果不得手改为 `passed`。

按任务 `validation` 顺序重跑全部配置项，任何命令非零退出即停止该项：

| 配置项 | 与包装器一致的命令 |
| --- | --- |
| `platform` | 根目录：`mvn -B -ntp -Pdev -f services/platform/pom.xml clean verify` |
| `ai` | 根目录：`mvn -B -ntp -Pci -f services/ai/pom.xml clean verify` |
| `frontend` | 在 `services/ai/frontend` 依次执行 `npm ci`、`npm run lint`、`npm run build` |

验证失败时先区分代码问题和工具、网络、沙箱拒绝；记录失败命令及退出信息。只修复批准范围内的问题，不修改权限、配置或 CI 来绕过失败。可信包装器仍须完成任务配置的全部验证。

验证前后均检查 `git diff --name-only HEAD` 和 `git ls-files --others --exclude-standard`；已提交的恢复工作还要检查 `git diff --name-only <baseSha> HEAD`。每个路径必须属于 `allowedPaths`，保留双方上游许可证。发布前审查完整差异及暂存区；`Publish-AgentPr.ps1` 会再次检查路径，并用 `git grep -IlE --cached` 扫描暂存内容中的凭据模式。扫描失败或命中即停止，只记录文件名，不输出令牌；模式扫描不能替代人工检查。

## 3. 恢复发布，复用 Draft PR

仅当本次可信 `result.json` 为 `passed`、任务与 Spec 哈希未变、HEAD 仍等于批准基线且待发布差异已核验时运行：

```powershell
pwsh -NoProfile -File scripts/agent/Publish-AgentPr.ps1 `
  -TaskFile $taskFile -RepositoryRoot . `
  -ResultFile ".agent/runs/$($task.taskId)/result.json" -Draft
```

该脚本检查指定仓库的 `origin`，暂存、扫描、提交、普通推送后，按 head 分支查找并复用已有开放 PR；不存在时创建 Draft。已有 PR 不会自动转成 Draft，须核对并保持其 Draft 状态。

若发布中断，先用 `git status --short`、`git log -1 --oneline`、`git ls-remote origin refs/heads/agent/<taskId>` 和 `gh pr list --repo Wonderchn/Ruoyi-Ai-AgentScope --head agent/<taskId> --state open` 核实实际进度：

- **尚未提交**：重新核对差异、验证证据、路径和扫描后，满足上述前提才可重跑发布脚本。
- **已提交但推送失败或结果不明**：脚本会因 HEAD 已改变而拒绝重跑。确认该提交正是已验证内容且远端无冲突后，维护者在原任务分支执行 `git push --set-upstream origin agent/<taskId>`；非快进拒绝时停止检查分歧，禁止强推。
- **已推送但 PR 创建失败或响应丢失**：先查找已有 PR 并复用；确认不存在后由维护者使用 `gh pr create --repo Wonderchn/Ruoyi-Ai-AgentScope --base main --head agent/<taskId> --draft` 创建，填写任务目标和真实验证结果。不要用回退提交、伪造结果或重复提交来重跑发布脚本。

## 4. GitHub Actions 失败是另一阶段

本地中断看运行目录；PR 检查失败看 GitHub 对应提交的 Actions run。本地 `passed` 不代表远端 CI 通过。可用 `gh pr checks <PR编号>` 定位检查，再用 `gh run view <run-id> --log-failed` 查看失败步骤，核对 run 的提交 SHA 是否为 PR 最新提交。

当前 CI 包含 `platform`、`ai`、`frontend`、`policy` 和汇总门禁 `ci-required`；其范围可能大于本地任务的验证项。临时基础设施失败可在确认提交未变后用 `gh run rerun <run-id> --failed` 重跑。代码失败须在任务分支、批准范围内修复并重新验证，普通推送后检查新提交的 CI；旧运行成功不能证明新提交通过。不要削弱检查或自动合并，PR 保持 Draft。

发现范围漂移、批准变更、哈希不符、基线不符或需要修改未批准路径时，停止执行与发布并报告维护者。设计需要变更时先走 Spec 更新和重新批准流程；不要直接修改批准文件或沿用旧结果。
