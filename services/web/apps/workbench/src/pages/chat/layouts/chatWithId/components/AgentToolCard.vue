<!--
  Agent 工具过程卡（F10 op1）。

  口径（协调者裁决①，2026-10-12）：普通工具卡只显示 **状态/结果(可展开)/耗时**；
  工具**调用参数只在确认卡展示**（这是参考产品的口径，也避免零后端改动下编造入参）。

  七态芯片：pending/running/awaiting/done/failed/denied/interrupted。
  - running：一行"执行中…"（结果未到，不给展开位）；
  - pending / awaiting / denied：没有返回可看，不渲染结果区（awaiting 的归属由外层时间线判定）；
  - failed：错因直接显在摘要位，不展开也看得到；
  - interrupted：无结果时不渲染卡片（有结果才给展开）。
-->
<script setup lang="ts">
import type { AgentBlock } from '../types';
import { computed } from 'vue';
import { errorSummary, formatDuration, summarizeToolResult, TOOL_STATE_LABEL } from '../agentTimeline';

const props = defineProps<{
  block: AgentBlock;
  /** 归属的助手消息 id（展开态回写用）。 */
  messageId?: string;
  /** 批内其它块（多工具批的耗时口径由外层决定，这里只管自己这条）。 */
  durationMs?: number;
}>();

const emit = defineEmits<{
  (event: 'toggle', payload: { messageId: string; blockId: number }): void;
}>();

const statusLabel = computed(() => TOOL_STATE_LABEL[props.block.status as keyof typeof TOOL_STATE_LABEL] ?? '');
const statusClass = computed(() => {
  switch (props.block.status) {
    case 'done':
      return 'agent-chip agent-chip--ok';
    case 'failed':
    case 'interrupted':
      return 'agent-chip agent-chip--err';
    case 'running':
      return 'agent-chip agent-chip--run';
    default:
      // denied 走静字：用户自己按的取消不是出错
      return 'agent-chip agent-chip--idle';
  }
});

/** 结果文本（终态才有）。 */
const resultText = computed(() => props.block.result ?? '');
/** 失败摘要 / 成功摘要。 */
const summary = computed(() => {
  if (resultText.value === '')
    return '';
  return props.block.status === 'failed' ? errorSummary(resultText.value) : summarizeToolResult(resultText.value);
});
const durationText = computed(() => formatDuration(props.durationMs ?? props.block.durationMs));

/** 是否有可展开的返回体（running 与"从未执行"的块没有）。 */
const expandable = computed(() => {
  if (props.block.status === 'pending' || props.block.status === 'awaiting' || props.block.status === 'denied')
    return false;
  if (props.block.status === 'running')
    return false;
  if (props.block.status === 'interrupted' && resultText.value === '')
    return false;
  return true;
});

function toggle() {
  if (!expandable.value || !props.messageId)
    return;
  emit('toggle', { messageId: props.messageId, blockId: props.block.id });
}

/** 原始返回全文（对象美化；非 JSON 原样）。 */
const fullText = computed(() => {
  const raw = resultText.value;
  if (raw === '')
    return '（空返回）';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2) ?? raw;
  }
  catch {
    return raw;
  }
});

defineExpose({ statusLabel, summary, durationText, expandable });
</script>

<template>
  <div class="agent-tool-card" :data-status="block.status ?? ''" data-testid="agent-tool-card">
    <div class="agent-tool-head">
      <span class="agent-tool-name">{{ block.displayName || block.name || '工具' }}</span>
      <span :class="statusClass" data-testid="agent-tool-status">{{ statusLabel }}</span>
      <span v-if="durationText" class="agent-tool-dur" data-testid="agent-tool-duration">
        · {{ durationText }}
      </span>
    </div>

    <div v-if="block.status === 'running'" class="agent-tool-running" data-testid="agent-tool-running">
      执行中…
    </div>

    <template v-else-if="expandable">
      <button
        type="button"
        class="agent-tool-summary"
        :aria-expanded="Boolean(block.open)"
        :data-failed="block.status === 'failed'"
        data-testid="agent-tool-summary"
        @click="toggle"
      >
        <span class="agent-caret">{{ block.open ? '▾' : '▸' }}</span>
        <span class="agent-tool-preview">{{ summary }}</span>
      </button>
      <pre v-if="block.open" class="agent-pre" data-testid="agent-tool-result">{{ fullText }}</pre>
    </template>
  </div>
</template>

<style scoped lang="scss">
.agent-tool-card {
  margin: 4px 0;
  font-size: 13px;

  .agent-tool-head {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;

    .agent-tool-name {
      font-weight: 500;
      color: var(--apple-text);
      word-break: break-all;
    }

    .agent-tool-dur {
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      color: var(--apple-tertiary);
    }
  }

  .agent-chip {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    padding: 1px 9px;
    font-size: 12px;
    line-height: 18px;
    border-radius: 999px;

    &--idle {
      color: var(--apple-secondary);
      background: var(--apple-info-soft);
    }

    &--run {
      color: var(--apple-warning);
      background: var(--apple-warning-soft);
    }

    &--ok {
      color: var(--apple-success);
      background: var(--apple-success-soft);
    }

    &--err {
      color: var(--apple-danger);
      background: var(--apple-danger-soft);
    }
  }

  .agent-tool-running {
    margin-top: 4px;
    font-size: 12px;
    color: var(--apple-secondary);
  }

  .agent-tool-summary {
    display: flex;
    gap: 5px;
    align-items: flex-start;
    margin-top: 5px;
    padding: 2px 0;
    font-family: var(--apple-font);
    font-size: 12px;
    color: var(--apple-secondary);
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: none;
    border-radius: 8px;

    &:hover {
      color: var(--apple-text);
    }

    &[data-failed='true'] {
      color: var(--apple-danger);
    }

    .agent-caret {
      flex-shrink: 0;
    }

    .agent-tool-preview {
      word-break: break-all;
    }
  }

  .agent-pre {
    max-height: 300px;
    padding: 12px 14px;
    margin: 6px 0 0;
    overflow: auto;
    font-family: var(--apple-font-mono);
    font-size: 12px;
    line-height: 1.6;
    color: var(--apple-text);
    white-space: pre-wrap;
    word-break: break-word;
    background: var(--apple-bg);
    border-radius: 12px;
  }
}
</style>
