import type { RequestIdentity } from '@ruoyi/events/rag';
/**
 * 聊天页的运行编排（Vue 响应式薄壳）。
 *
 * ## 为什么单独成文件
 *
 * `chatWithId/index.vue` 的 `setup()` 无法被 `node:test` 直接执行（SFC + 浏览器依赖），
 * 而"发送 → 受理 → 事件 → 终态 → 失败显示 → 权限失效清空"**全部是这段编排的行为**。
 * 把它抽成普通 TS 模块后：
 * - 它可以在测试里用**真实**的 `createRunChatClient`（真 SSE 帧解析、真 seq 连续性状态机）
 *   驱动，用注入的 fetch 提供字节流；
 * - 页面只剩"把状态画出来 + 把气泡接上"，不再各自实现一套协议。
 *
 * ## 不变量
 *
 * 1. **权限失效即清空**：`authEpoch` 变化时中止在飞的流、清掉内容与错误（`reset()`）。
 * 2. **失败必须显示**：任何失败都落到 `error`（页面用 `ElAlert` 渲染），不允许只有 console。
 * 3. **终态只处理一次**，且 `run.terminal` 的 `FAILED/CANCELLED` 一律按失败展示
 *    （例如 `NO_AUTHORIZED_SCOPE` 不得被降级成"没有检索到的普通回答"）。
 * 4. **取消是真实请求**：`cancel()` 先中止本地流，再调 `POST /runs/{id}/cancel`；
 *    取消失败也要显示（不能假装已取消）。
 */
import type {
  RunChatClient,
  RunEventUpdate,
  RunFailure,
  RunStepUpdate,
  RunTerminalUpdate,
  RunUpdate,
} from '../../../../api/chat/run-chat';
import { computed, ref, shallowRef, watch } from 'vue';
// 相对路径而不是 `@/…`：`apps/workbench/tests/ts-loader.mjs` 只解析相对路径与真实依赖，
// 用别名会让这个"页面行为层"无法被 node:test 直接覆盖（本模块的可测性是刻意保证的）。
import { createRunChatClient, RunChatFailure, runFailureMessage } from '../../../../api/chat/run-chat';

export interface ChatRunHooks {
  /** 受理成功（`runId` 可作为运行面板的锚点）。 */
  onAccepted?: (update: Extract<RunUpdate, { kind: 'accepted' }>) => void;
  /** 助手正文增量（已确认 `dropped` 标记）。 */
  onDelta?: (text: string, dropped: boolean) => void;
  /** 步骤开始/完成（工具过程要保留，不能压平）。 */
  onStep?: (update: RunStepUpdate) => void;
  /** 用量事件。 */
  onUsage?: (payload: unknown) => void;
  /** 终态（**只调用一次**）。 */
  onTerminal?: (update: RunTerminalUpdate) => void;
  /** 未单独建模的其它事件（审批/核对/错误等），原样交给页面。 */
  onEvent?: (update: RunEventUpdate) => void;
}

export interface UseChatRunOptions {
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
  baseUrl?: string;
  clientId?: string;
  /** 注入运行时客户端（测试用）；缺省用真实 `createRunChatClient`。 */
  client?: RunChatClient;
  hooks?: ChatRunHooks;
  /** 终态失败文案注入（默认 `runFailureMessage`）。 */
  failureMessage?: (failure: RunFailure) => string;
}

export interface SubmitRunInput {
  text: string;
  conversationId?: string;
  resourceRefs?: Array<{ type: string; id: string }>;
}

export function useChatRun(options: UseChatRunOptions) {
  const failureText = options.failureMessage ?? runFailureMessage;
  const client = options.client ?? createRunChatClient({
    baseUrl: options.baseUrl,
    clientId: options.clientId,
    identity: options.identity,
    onAuthExpired: options.onAuthExpired,
  });

  const running = ref(false);
  /** 用户可见的失败原因（页面必须渲染）。 */
  const error = ref('');
  /** 用户可见的非失败提示（重放/补齐/审批等）。 */
  const notice = ref('');
  const runId = ref('');
  const runStatus = ref('');
  /** 最近一次 `run.usage` 载荷（原文，不解释）。 */
  const usage = shallowRef<unknown>(null);
  /** 终态（若已到达）。 */
  const terminal = shallowRef<RunTerminalUpdate | null>(null);
  /** 上一次终态失败码（诊断用，也是"无授权不扩全库"的可观测证据）。 */
  const terminalErrorCode = ref('');

  let controller: AbortController | null = null;
  /**
   * 上一次观察到的身份 epoch（只用于**检测变化**）。
   *
   * ⚠️ 不要把它当作"本轮提交的 epoch"：检测到变化时它会被更新成新值，
   * 于是"这一轮是否属于当前身份"的判断会永远为真（这正是第一版实现漏掉的点，
   * 由 `tests/use-chat-run.test.ts` 的"权限失效清空"用例抓出来）。
   * 每轮提交自己在 `submit()` 里捕获 `submitEpoch`。
   */
  let lastSeenEpoch = options.identity().epoch;
  /**
   * 本次中止是否由用户点"取消"触发。
   *
   * 用途：中止后**不能**无条件写"已取消"提示 —— 如果服务端 `run.cancel` 被拒绝
   * （如 403），界面必须显示失败而不是"已取消"。取消路径自己负责最终文案。
   */
  let cancelRequested = false;

  const isFailedTerminal = computed(() =>
    terminal.value !== null && (terminal.value.status === 'FAILED' || terminal.value.status === 'CANCELLED'),
  );

  /** 权限失效 / 离开页面时的清空：中止流 + 清状态。 */
  function reset() {
    controller?.abort();
    controller = null;
    cancelRequested = false;
    running.value = false;
    error.value = '';
    notice.value = '';
    runId.value = '';
    runStatus.value = '';
    usage.value = null;
    terminal.value = null;
    terminalErrorCode.value = '';
  }

  // 身份变化（登出/换人）→ 在飞请求作废 + 清空。
  watch(() => options.identity().epoch, (next) => {
    if (next !== lastSeenEpoch) {
      lastSeenEpoch = next;
      reset();
    }
  }, { flush: 'sync' });

  function applyUpdate(update: RunUpdate) {
    switch (update.kind) {
      case 'accepted':
        runId.value = update.runId;
        runStatus.value = update.status;
        if (update.replayed)
          notice.value = '服务端复用了同一幂等键的既有运行（replayed=true），未重复受理。';
        options.hooks?.onAccepted?.(update);
        break;
      case 'status':
        if (update.status)
          runStatus.value = update.status;
        break;
      case 'step':
        options.hooks?.onStep?.(update);
        break;
      case 'delta':
        if (update.dropped)
          notice.value = '服务端标记部分增量被丢弃（dropped=true），本轮正文可能不完整。';
        if (update.text)
          options.hooks?.onDelta?.(update.text, update.dropped);
        break;
      case 'usage':
        usage.value = update.payload;
        options.hooks?.onUsage?.(update.payload);
        break;
      case 'event':
        options.hooks?.onEvent?.(update);
        break;
      case 'heartbeat':
        break;
      case 'reconnect':
        notice.value = update.reason === 'gap'
          ? `检测到事件序号缺口，正在从 seq=${update.afterSeq ?? '?'} 之后补齐。`
          : '事件流中断，正在重连。';
        break;
      case 'cursor-expired':
        notice.value = update.message;
        break;
      case 'snapshot':
        notice.value = `事件流已结束但未收到终态帧；服务端快照状态：${update.snapshot.status ?? 'UNKNOWN'}。`;
        break;
      case 'terminal':
        terminal.value = update;
        runStatus.value = update.status;
        terminalErrorCode.value = update.errorCode ?? '';
        options.hooks?.onTerminal?.(update);
        if (update.status === 'FAILED' || update.status === 'CANCELLED') {
          error.value = update.errorCode
            ? `服务端终态失败：${update.errorCode}${update.errorCode === 'NO_AUTHORIZED_SCOPE' ? '（没有可用的知识库授权：本次未检索、未外发模型）' : ''}`
            : `服务端终态失败：${update.status}`;
        }
        break;
      default:
        break;
    }
  }

  /**
   * 读取终态。
   *
   * 必须经过**函数调用**读取：`submit()` 开头有 `terminal.value = null`，而 TS 的属性收窄
   * 不会被中间的 `applyUpdate(...)` 调用重置 —— 直接写 `terminal.value` 会被判定为 `null`，
   * 于是 `if (final === null) return` 之后剩下的类型是 `never`（实测报错形态）。
   * 经函数返回声明的 `RunTerminalUpdate | null` 才是这里真正的不变量。
   */
  function currentTerminal(): RunTerminalUpdate | null {
    return terminal.value;
  }

  /**
   * 提交一轮普通聊天（`rag.chat`）。
   *
   * 返回 `true` 表示流正常走到 `run.terminal`；`false` 表示失败/被取消（原因在 `error`）。
   */
  async function submit(input: SubmitRunInput): Promise<boolean> {
    if (running.value)
      return false;
    // 本轮提交锁定的身份：身份一旦变化，这一轮的任何写回都必须被丢弃。
    const submitEpoch = options.identity().epoch;
    cancelRequested = false;
    error.value = '';
    notice.value = '';
    terminal.value = null;
    terminalErrorCode.value = '';
    usage.value = null;
    controller = new AbortController();
    running.value = true;

    try {
      for await (const update of client.run({
        text: input.text,
        conversationId: input.conversationId,
        resourceRefs: input.resourceRefs,
        signal: controller.signal,
      })) {
        if (options.identity().epoch !== submitEpoch)
          return false;
        applyUpdate(update);
      }
      // 经函数读取（见 `currentTerminal` 的说明）：直接读 `terminal.value` 会被旧收窄判成 `null`。
      const final = currentTerminal();
      if (options.identity().epoch !== submitEpoch)
        return false;
      if (final === null) {
        // 运行时客户端保证"没有终态就抛错"，这里是第二道兜底：绝不把"流结束"当成功。
        error.value = '事件流结束但未收到 run.terminal，运行结果未知。';
        return false;
      }
      return final.status !== 'FAILED' && final.status !== 'CANCELLED';
    }
    catch (err) {
      // 身份已变化：本轮流已经作废，**不得**再往界面上写任何状态（reset() 已清空）。
      if (options.identity().epoch !== submitEpoch)
        return false;
      if (err instanceof RunChatFailure) {
        if (err.failure.kind === 'aborted') {
          // 取消路径会自己写最终文案（成功=已请求取消；失败=显示失败原因）。
          if (!cancelRequested)
            notice.value = '已取消本次运行。';
          return false;
        }
        error.value = failureText(err.failure);
        return false;
      }
      error.value = err instanceof Error ? err.message : '运行失败';
      return false;
    }
    finally {
      running.value = false;
      controller = null;
    }
  }

  /**
   * 取消：先中止本地流，再请求服务端取消（`run.cancel`）。
   *
   * 服务端取消失败时**如实显示**（例如没有 `ai:run:cancel` 权限 → 403）。
   */
  async function cancel(): Promise<boolean> {
    const id = runId.value;
    cancelRequested = true;
    controller?.abort();
    controller = null;
    running.value = false;
    if (id === '')
      return false;
    try {
      await client.cancel(id);
      error.value = '';
      notice.value = '已请求服务端取消本次运行。';
      return true;
    }
    catch (err) {
      const failure = (err as { failure?: RunFailure })?.failure;
      error.value = failure ? failureText(failure) : (err instanceof Error ? err.message : '取消失败');
      return false;
    }
  }

  return {
    running,
    error,
    notice,
    runId,
    runStatus,
    usage,
    terminal,
    terminalErrorCode,
    isFailedTerminal,
    submit,
    cancel,
    reset,
  };
}
