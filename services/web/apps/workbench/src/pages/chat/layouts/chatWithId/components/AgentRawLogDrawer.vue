<!--
  Agent 原始帧抽屉（F10 op5）。

  来源是**前端本地帧缓冲**（协调者裁决②，2026-10-12）：本次连接收到的每一条 SSE 帧
  （含 `ai-delivery` 许可注释帧与心跳）逐条列出 —— 帧名 + 时间 + 原文。
  run 侧 `event-records`（`ai_run_event` + `afterSeq` 回放）登记为**等价面**，
  本包不新建服务端接口，也不泄露凭据（抽屉只显示服务端下发的帧体）。

  上限 `AGENT_RAW_FRAME_LIMIT`（2000，客户端缓冲截尾）；换会话即清。
-->
<script setup lang="ts">
import type { AgentRawFrame } from '../types';
import { nextTick, ref, watch } from 'vue';

const props = defineProps<{
  open: boolean;
  frames: AgentRawFrame[];
  /** 抽屉条上的归属说明（会话 id / 连接类型）。 */
  context?: string;
}>();

const emit = defineEmits<{
  (event: 'update:open', open: boolean): void;
}>();

const bodyRef = ref<HTMLElement | null>(null);
// 贴着底就跟随最新帧；往上翻查历史时不打扰
const stick = ref(true);

watch(() => props.frames.length, async () => {
  if (!props.open || !stick.value)
    return;
  await nextTick();
  const el = bodyRef.value;
  if (el)
    el.scrollTop = el.scrollHeight;
});

function onScroll() {
  const el = bodyRef.value;
  if (!el)
    return;
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
}

function renderData(data: unknown): string {
  if (typeof data === 'string')
    return data;
  try {
    return JSON.stringify(data, null, 2) ?? String(data);
  }
  catch {
    return String(data);
  }
}

function close() {
  emit('update:open', false);
}
</script>

<template>
  <div v-if="open" class="agent-rawlog" data-testid="agent-rawlog">
    <div class="agent-rawlog-head">
      <span class="agent-rawlog-title" data-testid="agent-rawlog-title">原始帧 · {{ frames.length }}</span>
      <span v-if="context" class="agent-rawlog-context">{{ context }}</span>
      <button type="button" class="agent-rawlog-close" aria-label="关闭" data-testid="agent-rawlog-close" @click="close">
        ✕
      </button>
    </div>
    <div ref="bodyRef" class="agent-rawlog-body" data-testid="agent-rawlog-body" @scroll="onScroll">
      <div v-if="frames.length === 0" class="agent-rawlog-empty" data-testid="agent-rawlog-empty">
        还没有帧，发一条消息看看。
      </div>
      <template v-else>
        <div v-for="frame in frames" :key="frame.id" class="agent-frame" data-testid="agent-raw-frame">
          <div class="agent-frame-head">
            <span class="agent-frame-ts">{{ frame.ts }}</span>
            <span class="agent-frame-name">{{ frame.name }}</span>
          </div>
          <pre class="agent-frame-pre">{{ renderData(frame.data) }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.agent-rawlog {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  margin: 8px 12px 0;
  overflow: hidden;
  font-size: 12px;
  color: #f5f5f7;
  background: #1d1d1f;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;

  .agent-rawlog-head {
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 10px 14px;
    background: #2c2c2e;
    border-bottom: 1px solid rgba(255, 255, 255, 0.1);

    .agent-rawlog-title {
      font-weight: 600;
    }

    .agent-rawlog-context {
      font-size: 11px;
      color: #a1a1a6;
    }

    .agent-rawlog-close {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      margin-left: auto;
      color: #a1a1a6;
      cursor: pointer;
      background: transparent;
      border: none;
      border-radius: 8px;

      &:hover {
        color: #ffffff;
        background: rgba(255, 255, 255, 0.1);
      }
    }
  }

  .agent-rawlog-body {
    flex: 1;
    padding: 10px 14px 14px;
    overflow: auto;

    .agent-rawlog-empty {
      padding: 12px 0;
      color: #a1a1a6;
    }

    .agent-frame {
      padding: 6px 0;

      .agent-frame-head {
        display: flex;
        gap: 8px;

        .agent-frame-ts {
          font-variant-numeric: tabular-nums;
          color: #86868b;
        }

        .agent-frame-name {
          color: #6fb6ff;
          word-break: break-all;
        }
      }

      .agent-frame-pre {
        margin: 4px 0 0;
        font-family: var(--apple-font-mono);
        line-height: 1.6;
        white-space: pre-wrap;
        word-break: break-word;
      }
    }
  }
}
</style>
