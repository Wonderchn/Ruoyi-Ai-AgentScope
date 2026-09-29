# 隔离的 headless 任务

从干净的 `main` 检出启动已批准的任务；其 `HEAD` 必须等于任务文件中的 `baseSha`，任务 Spec 的哈希也必须与批准记录一致。在仓库根目录运行：

```powershell
$taskId = '<task-id>'
pwsh -File scripts/agent/Start-IsolatedTask.ps1 -TaskFile ".agent/tasks/$taskId.json" -RepositoryRoot .
```

脚本在仓库的同级目录 `<repo-name>.agent-worktrees/<task-id>` 创建工作树，并在其中使用 `agent/<task-id>` 分支。任务报告位于该工作树的 `.agent/runs/<task-id>/result.json`；启动命令也会输出工作树和报告路径。

仅在报告的 `status` 为 `passed` 后，进入该工作树并发布 Draft PR：

```powershell
pwsh -File scripts/agent/Publish-AgentPr.ps1 -TaskFile ".agent/tasks/$taskId.json" -RepositoryRoot . -ResultFile ".agent/runs/$taskId/result.json" -Draft
```

如果任务失败或中断，保留现有工作树，不要再次启动相同任务。在工作树中查看 `git status --short`、`git diff`、`.agent/runs/<task-id>/result.json` 的 `reason` 和 `validationPassed`，以及同目录的 `codex.stderr.log`、`codex.jsonl` 和 `agent-final.json`。

- `codex.stderr.log` 可以为空，`agent-final.json` 也可能不存在。核对本次 `codex.jsonl`、实际文件和进程记录；不能只凭缺少 `file_change` 事件或空 stderr 判定没有产出。
- 中断较早时可能没有 `result.json`；此时结合启动命令的错误输出与已生成的日志排查。无 `result.json` 的分支尚未经过执行期中断实测，不得将缺失结果视为通过。

Draft PR 供人工审查；合并和部署仍是各自独立的受审操作。
