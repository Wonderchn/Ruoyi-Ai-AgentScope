/**
 * F14:2 多 Agent 应用市场的纯构造层（无 `@/` 别名、无 Vue/hook-fetch 依赖）。
 *
 * 单独成文件的原因与 `flow-market.ts` 相同：`node:test` 要能直接跑真实构造逻辑，
 * 这样"市场打哪个 URL / 使用提交什么 body"不是靠读源码猜，而是可执行判据。
 *
 * 端点契约（由协调者单写 ROUTES 后生效）：
 * - 列表：`GET /api/ai/v1/multi-agent/public/search`，参数 `current/size/keyword`；
 * - 详情：`GET /api/ai/v1/multi-agent/public/{uuid}`；
 * - 使用：`POST /api/ai/v1/runs`，action=`agent.supervisor`，input 带可选 `appId`。
 */

/** 公开多 Agent 列表路径（网关白名单逐条登记，不是通配）。 */
export const MULTI_AGENT_SEARCH_PATH = '/api/ai/v1/multi-agent/public/search';
/** 公开详情前缀。 */
export const MULTI_AGENT_DETAIL_PREFIX = '/api/ai/v1/multi-agent/public/';

/** 派发键/版本（与 `SupervisorProtocol` 逐字一致）。 */
export const SUPERVISOR_ACTION = 'agent.supervisor';
export const SUPERVISOR_VERSION = 'supervisor-v1';

/** 名册/文本边界（与 `SupervisorProtocol` 逐字一致）。 */
export const SUPERVISOR_MIN_AGENTS = 2;
export const SUPERVISOR_MAX_AGENTS = 3;
export const SUPERVISOR_TEXT_MAX = 4096;
export const SUPERVISOR_INSTRUCTION_MAX = 1024;
export const SUPERVISOR_MAX_TOKENS = 2000;
export const SUPERVISOR_MAX_TOKENS_CEILING = 8192;
export const SUPERVISOR_MAX_WALL_CLOCK_SECONDS = 120;
export const SUPERVISOR_MAX_WALL_CLOCK_SECONDS_CEILING = 600;

const AGENT_NAME = /^[a-z][a-z0-9_-]{1,15}$/;
/** 应用 id 是 V43 {@code uuid varchar(32)}：与工作流 uuid 同一形状（32 位小写十六进制）。 */
const APP_ID = /^[0-9a-f]{32}$/;

export interface MultiAgentAgent {
  name: string;
  instruction: string;
}

/** 市场详情投影：只含 uuid/name/description/mode/agents（无所有者/租户/开关列）。 */
export interface MultiAgentApp {
  uuid: string;
  name: string;
  description: string;
  mode: string;
  agents: MultiAgentAgent[];
}

export interface MultiAgentAppPage {
  records: MultiAgentApp[];
  total: number;
  current: number;
  size: number;
}

export interface SupervisorBudget {
  maxTokens?: number;
  maxWallClockSeconds?: number;
}

export interface SupervisorRunBody {
  schemaVersion: 1;
  action: 'agent.supervisor';
  agentVersion: 'supervisor-v1';
  input: {
    text: string;
    agents: MultiAgentAgent[];
    appId?: string;
  };
  resourceRefs: [];
  budget: { maxTokens: number; maxWallClockSeconds: number };
}

/** 发出请求前的本地形状错误（不是服务端拒绝）。 */
export class SupervisorInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SupervisorInputError';
  }
}

export interface MultiAgentSearchParams {
  current?: number;
  size?: number;
  keyword?: string;
}

/**
 * 构造公开列表 URL。
 *
 * 只发送服务端认识的三个参数；`currentPage/pageSize` 会被忽略（分页失效），
 * 因此不在这里出现。
 */
export function buildMultiAgentSearchUrl(params: MultiAgentSearchParams = {}): string {
  const query = new URLSearchParams();
  if (params.current !== undefined)
    query.set('current', String(params.current));
  if (params.size !== undefined)
    query.set('size', String(params.size));
  const keyword = typeof params.keyword === 'string' ? params.keyword.trim() : '';
  if (keyword !== '')
    query.set('keyword', keyword);
  const suffix = query.toString();
  return suffix === '' ? MULTI_AGENT_SEARCH_PATH : `${MULTI_AGENT_SEARCH_PATH}?${suffix}`;
}

/** 公开详情路径（uuid 必须 URL 编码）。 */
export function multiAgentDetailPath(uuid: string): string {
  return `${MULTI_AGENT_DETAIL_PREFIX}${encodeURIComponent(uuid)}`;
}

/** 名册形状与受理同一套约束；不一致时前端先失败，不发必然 400 的请求。 */
export function validateSupervisorAgents(agents: MultiAgentAgent[]): void {
  if (!Array.isArray(agents) || agents.length < SUPERVISOR_MIN_AGENTS || agents.length > SUPERVISOR_MAX_AGENTS)
    throw new SupervisorInputError(`名册必须包含 ${SUPERVISOR_MIN_AGENTS}..${SUPERVISOR_MAX_AGENTS} 个 Agent`);
  const names = new Set<string>();
  for (const agent of agents) {
    if (agent === null || typeof agent !== 'object')
      throw new SupervisorInputError('每个 Agent 必须是对象');
    if (typeof agent.name !== 'string' || !AGENT_NAME.test(agent.name))
      throw new SupervisorInputError(`Agent 名称必须匹配 ${AGENT_NAME.source}`);
    if (names.has(agent.name))
      throw new SupervisorInputError(`Agent 名称重复：${agent.name}`);
    names.add(agent.name);
    if (typeof agent.instruction !== 'string' || agent.instruction.trim() === '')
      throw new SupervisorInputError('Agent instruction 不能为空');
    if (agent.instruction.length > SUPERVISOR_INSTRUCTION_MAX)
      throw new SupervisorInputError(`Agent instruction 不能超过 ${SUPERVISOR_INSTRUCTION_MAX} 字符`);
  }
}

function clamp(value: number | undefined, fallback: number, min: number, max: number): number {
  if (value === undefined || !Number.isFinite(value))
    return fallback;
  return Math.min(max, Math.max(min, Math.trunc(value)));
}

/**
 * 构造 Supervisor 受理体（“使用”入口）。
 *
 * body 是白名单：只发 `schemaVersion/action/agentVersion/input/resourceRefs/budget`；
 * `input` 只含 `text/agents` 与可选 `appId`。模型/工具/系统提示词等客户端字段一律不发。
 * 无 `appId` 时与旧 Supervisor 调用逐字节一致（服务端旧行为不变）。
 */
export function buildSupervisorRunBody(input: {
  text: string;
  agents: MultiAgentAgent[];
  appId?: string;
  budget?: SupervisorBudget;
}): SupervisorRunBody {
  const text = typeof input.text === 'string' ? input.text.trim() : '';
  if (text === '')
    throw new SupervisorInputError('输入不能为空');
  if (text.length > SUPERVISOR_TEXT_MAX)
    throw new SupervisorInputError(`输入不能超过 ${SUPERVISOR_TEXT_MAX} 字符`);
  validateSupervisorAgents(input.agents);

  const body: SupervisorRunBody = {
    schemaVersion: 1,
    action: SUPERVISOR_ACTION,
    agentVersion: SUPERVISOR_VERSION,
    input: {
      text,
      agents: input.agents.map(agent => ({ name: agent.name, instruction: agent.instruction })),
    },
    resourceRefs: [],
    budget: {
      maxTokens: clamp(input.budget?.maxTokens, SUPERVISOR_MAX_TOKENS, 1, SUPERVISOR_MAX_TOKENS_CEILING),
      maxWallClockSeconds: clamp(input.budget?.maxWallClockSeconds, SUPERVISOR_MAX_WALL_CLOCK_SECONDS, 1, SUPERVISOR_MAX_WALL_CLOCK_SECONDS_CEILING),
    },
  };
  if (input.appId !== undefined) {
    const appId = input.appId.trim();
    if (appId === '')
      throw new SupervisorInputError('appId 不能为空');
    if (!APP_ID.test(appId))
      throw new SupervisorInputError(`appId 必须匹配 ${APP_ID.source}`);
    body.input.appId = appId;
  }
  return body;
}
