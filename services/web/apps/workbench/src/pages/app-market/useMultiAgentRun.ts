import type { AgentRunApi } from '@/api/ai/agent-run';
import type { MultiAgentApp } from '@/api/chat/multi-agent-market';
import type { RequestIdentity, RunSubmitBody } from '@ruoyi/events/rag';
import { newRequestId } from '@ruoyi/events/rag';
import { shallowRef } from 'vue';
import { createAgentRunApi } from '@/api/ai/agent-run';
import { buildSupervisorRunBody } from '@/api/chat/multi-agent-market';

/**
 * 市场“使用”入口的运行编排（F14:2）。
 *
 * 复用 Agent 运行面的传输层（身份守卫 / 整数 envelope / 401 处理），只替换受理体：
 * action=`agent.supervisor`，input 带可选 `appId`。受理成功后轮询 `GET /runs/{id}`
 * 直到终态；终态只认 `SUCCEEDED/FAILED/CANCELLED`，答案取 `terminalResult.answer`
 * （Supervisor 的 finalRef 形状，见 `SupervisorRunExecutor.finish`）。
 *
 * 轮询而不是 SSE 是刻意的最小切片：市场只展示最终答案，不需要流式增量；
 * 事件流 API 与 rag.chat 共用，后续如需增量再切到 `openRunStream`。
 */

const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const DEFAULT_POLL_MS = 1500;
const DEFAULT_DEADLINE_MS = 120_000;

export interface MultiAgentRunOptions {
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  /** 测试注入；缺省用真实 Agent 运行面客户端。 */
  client?: Pick<AgentRunApi, 'submit' | 'getRun'>;
  pollMs?: number;
  deadlineMs?: number;
}

/** 从终态 resultRef 中读 Supervisor 答案（finalRef = {answer, delegations, ...}）。 */
export function readSupervisorAnswer(result: unknown): string {
  if (result === null || typeof result !== 'object')
    return '';
  const record = result as Record<string, unknown>;
  if (typeof record.answer === 'string')
    return record.answer;
  const nested = record.result;
  if (nested !== null && typeof nested === 'object'
    && typeof (nested as Record<string, unknown>).answer === 'string')
    return (nested as Record<string, unknown>).answer as string;
  return '';
}

export function useMultiAgentRun(options: MultiAgentRunOptions) {
  const api = options.client ?? createAgentRunApi({
    identity: options.identity,
    onAuthExpired: options.onAuthExpired,
  });
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
  const deadlineMs = options.deadlineMs ?? DEFAULT_DEADLINE_MS;
  const using = shallowRef(false);
  const answer = shallowRef('');
  const error = shallowRef('');
  const runId = shallowRef('');

  async function submit(app: MultiAgentApp, text: string): Promise<void> {
    using.value = true;
    answer.value = '';
    error.value = '';
    runId.value = '';
    try {
      const body = buildSupervisorRunBody({ text, agents: app.agents, appId: app.uuid });
      // 共享 `RunSubmitBody.action` 的联合尚未包含 `agent.supervisor`
      // （`packages/events` 不在本切片允许路径）；wire body 完全一致，这里只做类型窄化。
      const accepted = await api.submit(body as unknown as RunSubmitBody, newRequestId());
      runId.value = accepted.runId;
      const deadline = Date.now() + deadlineMs;
      while (Date.now() < deadline) {
        const snapshot = await api.getRun(accepted.runId);
        if (TERMINAL_STATUSES.has(snapshot.status)) {
          if (snapshot.status === 'SUCCEEDED')
            answer.value = readSupervisorAnswer(snapshot.terminalResult);
          else
            error.value = snapshot.errorCode || `运行状态：${snapshot.status}`;
          return;
        }
        await new Promise(resolve => setTimeout(resolve, pollMs));
      }
      error.value = '运行超时，请稍后在运行记录中查看。';
    }
    catch (failure) {
      error.value = failure instanceof Error ? failure.message : '使用应用失败，请重试。';
    }
    finally {
      using.value = false;
    }
  }

  function reset() {
    answer.value = '';
    error.value = '';
    runId.value = '';
  }

  return { using, answer, error, runId, submit, reset };
}
