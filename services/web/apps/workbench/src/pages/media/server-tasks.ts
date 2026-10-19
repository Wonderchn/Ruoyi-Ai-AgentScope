import type { MediaGenerationResponse, MediaTaskDTO } from '../../api/media/types';
import type { MediaTask, TaskState } from './types';

/** Providers whose bytes are only readable through the authenticated content endpoint. */
export const CONTROLLED_CONTENT_PROVIDERS = ['Tongyiwanx', 'atlas'];

const STATE_MESSAGES: Record<TaskState, string> = {
  submitting: '正在提交，请稍候。',
  waiting: '任务已提交，正在等待生成结果。',
  success: '生成完成，可以预览或保存结果。',
  failed: '服务未能完成生成，请查看失败原因。',
  paused: '任务结果未知，未自动重试；可稍后显式重试。',
};

/**
 * Stable wire status -> page state.
 *
 * `pending/processing` are still in flight; `completed/failed/unknown` are terminal.
 * Unknown values are treated as `paused` (never as success), so a new server status
 * cannot silently turn into a rendered result.
 */
export function wireStatusToState(status: string | undefined): TaskState {
  switch ((status ?? '').trim().toLowerCase()) {
    case 'pending':
    case 'queued':
    case 'processing':
    case 'running':
      return 'waiting';
    case 'completed':
    case 'succeeded':
    case 'success':
      return 'success';
    case 'failed':
    case 'error':
    case 'cancelled':
    case 'canceled':
      return 'failed';
    case 'unknown':
      return 'paused';
    default:
      return 'paused';
  }
}

function messageFor(dto: MediaTaskDTO, state: TaskState): string {
  const message = dto.message?.trim();
  if (message)
    return message;
  if (state === 'failed' && dto.errorCode)
    return `生成失败（${dto.errorCode}），请查看服务端原因。`;
  return STATE_MESSAGES[state];
}

/** Server task authority -> page task. The refresh path must produce the same shape. */
export function toMediaTask(dto: MediaTaskDTO): MediaTask {
  const state = wireStatusToState(dto.status);
  const provider = dto.provider ?? '';
  const createdAt = Date.parse(dto.createdAt);
  return {
    key: dto.id,
    serverId: dto.id,
    kind: dto.type,
    model: dto.model,
    modelLabel: dto.model,
    provider,
    prompt: dto.prompt,
    createdAt: Number.isFinite(createdAt) ? createdAt : Date.now(),
    state,
    message: messageFor(dto, state),
    errorCode: dto.errorCode ?? undefined,
    attempt: dto.attempt,
    previousTaskId: dto.previousTaskId ?? undefined,
    terminalUnknown: state === 'paused',
    result: {
      id: dto.id,
      status: dto.status,
      mimeType: dto.mimeType ?? undefined,
    },
  };
}

/** Merge a poll/query response into an existing task without inventing a success. */
export function applyServerOutcome(task: MediaTask, response: MediaGenerationResponse): MediaTask {
  const state = wireStatusToState(response.status);
  const id = response.id || task.serverId || task.result?.id;
  const message = response.message?.trim()
    || (state === 'failed' && response.errorCode
      ? `生成失败（${response.errorCode}），请查看服务端原因。`
      : STATE_MESSAGES[state]);
  return {
    ...task,
    serverId: id || task.serverId,
    state,
    message,
    errorCode: response.errorCode ?? task.errorCode,
    attempt: response.attempt ?? task.attempt,
    terminalUnknown: state === 'paused',
    result: {
      ...task.result,
      id: id || task.result?.id,
      status: response.status ?? task.result?.status,
      mimeType: response.mimeType ?? task.result?.mimeType,
    },
  };
}

/** Only FAILED/UNKNOWN server tasks may be retried explicitly (never an in-flight one). */
export function canRetryServerTask(task: MediaTask | undefined): boolean {
  return Boolean(task?.serverId && (task.state === 'failed' || task.terminalUnknown));
}

/** Success content is always fetched by task id through the authenticated endpoint. */
export function needsControlledContent(task: MediaTask | undefined): boolean {
  return Boolean(task?.state === 'success'
    && task.result?.id
    && CONTROLLED_CONTENT_PROVIDERS.includes(task.provider));
}

/** Replace the page list with the server order; keep transient local (audio/video) tasks. */
export function mergeServerTasks(current: readonly MediaTask[], dtos: readonly MediaTaskDTO[]): MediaTask[] {
  const local = current.filter(task => !task.serverId);
  return [...dtos.map(toMediaTask), ...local];
}
