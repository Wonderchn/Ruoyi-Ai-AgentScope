/**
 * P2 RAG 纯逻辑（无 Vue/请求依赖，供页面与 node:test 共用）。
 */

export interface Citation {
  docId: string;
  docName?: string;
  versionId: string;
  chunkKey: string;
  chunkIndex: number;
  pageFrom?: number | null;
  pageTo?: number | null;
  score?: number;
  viewRef?: string;
}

export interface RunSubmitBody {
  schemaVersion: 1;
  action: 'rag.chat' | 'document.ingest' | 'agent.run';
  agentVersion?: 'core-v1';
  input: Record<string, unknown>;
  resourceRefs: Array<{ type: string; id: string }>;
  budget?: { maxTokens?: number; maxWallClockSeconds?: number; maxSteps?: number; maxToolCalls?: number };
  retryOf?: string;
}

export function agentRunBody(kbId: string, text: string, ticket?: { title: string; details: string }, retryOf?: string, inheritActionId?: string): RunSubmitBody {
  return {
    schemaVersion: 1,
    action: 'agent.run',
    agentVersion: 'core-v1',
    input: { text, mode: ticket ? 'sandbox' : 'read', ...(ticket ? { ticket } : {}), ...(inheritActionId ? { inheritActionId } : {}) },
    resourceRefs: [{ type: 'knowledge_base', id: kbId }],
    budget: { maxTokens: 4000, maxSteps: 6, maxToolCalls: 6, maxWallClockSeconds: 600 },
    ...(retryOf ? { retryOf } : {}),
  };
}

export function chatRunBody(kbIds: string[], text: string, retryOf?: string): RunSubmitBody {
  return {
    schemaVersion: 1,
    action: 'rag.chat',
    input: { text },
    resourceRefs: kbIds.map(id => ({ type: 'knowledge_base', id })),
    budget: { maxTokens: 2000 },
    ...(retryOf ? { retryOf } : {}),
  };
}

/** 引用去重（同 doc/version/chunk 只保留一次）并保持顺序。 */
export function dedupeCitations(citations: Citation[]): Citation[] {
  const seen = new Set<string>();
  const result: Citation[] = [];
  for (const citation of citations) {
    const key = `${citation.docId}|${citation.versionId}|${citation.chunkKey}`;
    if (!seen.has(key)) {
      seen.add(key);
      result.push(citation);
    }
  }
  return result;
}

/** 从 run.terminal 事件 payload 提取答案与引用（缺失时返回空）。 */
export function terminalSummary(payload: unknown): { answer: string; citations: Citation[]; evidenceInsufficient: boolean } {
  const data = (payload ?? {}) as Record<string, unknown>;
  let result = (data.resultRef ?? data) as Record<string, unknown>;
  if (typeof result === 'string') {
    try {
      result = JSON.parse(result);
    }
    catch {
      result = {};
    }
  }
  const answer = typeof result.answer === 'string' ? result.answer : '';
  const citations = Array.isArray(result.citations) ? (result.citations as Citation[]) : [];
  return { answer, citations: dedupeCitations(citations), evidenceInsufficient: result.evidenceInsufficient === true };
}

/**
 * 服务端已验证终态的用户表达（R01）。
 *
 * 终态 FAILED/CANCELLED 必须原样给出服务端 errorCode——依赖不可用（AUTHORIZATION_UNAVAILABLE）、
 * 真实来源变化（SOURCE_CHANGED）等含义不同，不得吞成“流不完整”；只有在流里从未出现终态帧时
 * （isTerminalFrame=false）才允许表达为流中断，且不携带任何编造的结果。
 */
export function terminalFailureNote(status: string, errorCode: string, isTerminalFrame: boolean): string {
  if (status === 'FAILED' || status === 'CANCELLED') {
    return errorCode ? `服务端终态失败：${errorCode}` : `服务端终态失败：${status}`;
  }
  if (!isTerminalFrame)
    return '事件流在终态前中断（未收到 run.terminal）';
  return '';
}
