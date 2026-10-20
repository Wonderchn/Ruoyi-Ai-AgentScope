<!--
  Agent 写操作确认卡（F15 op2）。

  参考实现口径（`AgentTurn.tsx` ConfirmBox）：
  - **整卡一次裁决**，不逐条勾选；
  - 工具名 + 结构化入参（fields）+ **原始参数折叠**（`arguments` 原文 JSON）；
  - 裁决后按钮撤走只留结论 —— 已经发生的事不该再摆一副能改的样子；
  - 多调用同工具时公共字段提到卡头，每项只留差异，差异为空则不提。

  五态：pending（待确认）/ submitting（提交中）/ approved / denied / expired。
  `expired` 是**前端本地态**：服务端拒绝"已失效"或回查后卡已不在待批——
  后端的 confirm 终态只有 pending/approved/denied。

  身份/title：卡头显示 `title`（服务端给的会话标题）与逐项工具中文名（displayName 优先）。
-->
<script setup lang="ts">
import type { AgentBlock } from '../types';
import { computed } from 'vue';
import { CONFIRM_ITEM_STATE_LABEL, CONFIRM_STATE_LABEL, outcomeSummary, rawArgumentsOf, splitCommonFields } from '../agentTimeline';

const props = defineProps<{
  block: AgentBlock;
  /** 归属的助手消息 id（展开原始参数回写折叠态用）。 */
  messageId?: string;
  /** 服务端给的会话标题（卡头身份）。 */
  title?: string;
  /** 与 `block.calls` 同序的逐项执行结局（确认后续跑段的工具块）。 */
  outcomes?: (AgentBlock | undefined)[];
  /** 提交进行中（按钮禁用；由父级 `submitting` 状态驱动）。 */
  submitting?: boolean;
}>();

const emit = defineEmits<{
  (event: 'resolve', approved: boolean): void;
  (event: 'toggle', payload: { messageId: string; blockId: number }): void;
}>();

const calls = computed(() => props.block.calls ?? []);
const status = computed(() => props.block.status ?? 'pending');
const stateMeta = computed(() => CONFIRM_STATE_LABEL[status.value] ?? { label: '', lead: '以下操作已提交确认' });
const summaryText = computed(() => outcomeSummary(props.outcomes));
const { common, items } = computed(() => splitCommonFields(calls.value)).value;
const head = computed(() => (common.length > 0 ? calls.value[0] : undefined));
const raw = computed(() => rawArgumentsOf(calls.value));
/** 只有待裁决（pending）才给按钮；submitting 期间也收起来，避免重复提交。 */
const actionable = computed(() => status.value === 'pending' && !props.submitting);
const toggleEnabled = computed(() => raw.value.length > 0);

function toggleRaw() {
  emit('toggle', { messageId: props.messageId ?? '', blockId: props.block.id });
}

defineExpose({ stateMeta, summaryText, calls, raw, actionable });
</script>

<template>
  <div class="agent-confirm" :data-status="status" data-testid="agent-confirm-card">
    <p class="agent-confirm-lead" data-testid="agent-confirm-lead">
      {{ stateMeta.lead }}
      <span v-if="summaryText" class="agent-confirm-summary"> · {{ summaryText }}</span>
    </p>

    <div v-if="title" class="agent-confirm-title" data-testid="agent-confirm-title">
      {{ title }}
    </div>

    <!-- 同工具多项：公共参数与工具名提到卡头 -->
    <div v-if="head" class="agent-confirm-head">
      <div class="agent-confirm-name">
        <span>{{ head.displayName || head.name }}</span>
        <span class="agent-confirm-count">×{{ calls.length }}</span>
      </div>
      <dl class="agent-confirm-fields" data-testid="agent-confirm-fields">
        <template v-for="field in common" :key="field.name">
          <dt>{{ field.label }}</dt>
          <dd>{{ field.value }}</dd>
        </template>
      </dl>
    </div>

    <ul class="agent-confirm-list">
      <li v-for="(call, index) in calls" :key="call.toolCallId || `${call.name}-${index}`" data-testid="agent-confirm-call">
        <div class="agent-confirm-name">
          <span v-if="calls.length > 1" class="agent-confirm-no">#{{ index + 1 }}</span>
          <span v-if="!head">{{ call.displayName || call.name }}</span>
          <span
            v-if="outcomes?.[index]?.status && CONFIRM_ITEM_STATE_LABEL[outcomes[index]?.status as keyof typeof CONFIRM_ITEM_STATE_LABEL]"
            class="agent-chip"
            data-testid="agent-confirm-item-state"
          >
            {{ CONFIRM_ITEM_STATE_LABEL[outcomes[index]?.status as keyof typeof CONFIRM_ITEM_STATE_LABEL] }}
          </span>
        </div>
        <dl class="agent-confirm-fields" data-testid="agent-confirm-item-fields">
          <template v-for="field in items[index] ?? []" :key="field.name">
            <dt>{{ field.label }}</dt>
            <dd>{{ field.value }}</dd>
          </template>
        </dl>
      </li>
    </ul>

    <!-- 原始参数折叠：默认收起，展开是多段原始 JSON -->
    <div v-if="toggleEnabled" class="agent-confirm-raw">
      <button
        type="button"
        class="agent-tool-summary"
        :aria-expanded="Boolean(block.open)"
        data-testid="agent-confirm-raw-toggle"
        @click="toggleRaw"
      >
        <span class="agent-caret">{{ block.open ? '▾' : '▸' }}</span>
        <span>原始参数</span>
      </button>
      <template v-if="block.open">
        <pre v-for="(text, index) in raw" :key="index" class="agent-pre" data-testid="agent-confirm-raw-args">{{ text }}</pre>
      </template>
    </div>

    <div v-if="actionable" class="agent-confirm-actions">
      <button
        type="button"
        class="agent-confirm-btn agent-confirm-btn--primary"
        data-testid="agent-confirm-approve"
        @click="emit('resolve', true)"
      >
        确认执行
      </button>
      <button
        type="button"
        class="agent-confirm-btn"
        data-testid="agent-confirm-deny"
        @click="emit('resolve', false)"
      >
        取消
      </button>
    </div>
    <div v-else-if="status === 'submitting'" class="agent-confirm-wait" data-testid="agent-confirm-submitting">
      正在提交裁决…
    </div>
  </div>
</template>

<style scoped lang="scss">
.agent-confirm {
  padding: 14px 16px;
  margin: 6px 0;
  font-size: 13px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-left: 3px solid var(--apple-warning);
  border-radius: 14px;

  &[data-status='approved'] {
    background: var(--apple-success-soft);
    border-left-color: var(--apple-success);
  }

  &[data-status='denied'],
  &[data-status='expired'] {
    background: var(--apple-bg);
    border-left-color: var(--apple-separator-strong);
  }

  .agent-confirm-lead {
    margin: 0 0 8px;
    color: var(--apple-text);

    .agent-confirm-summary {
      color: var(--apple-secondary);
    }
  }

  .agent-confirm-title {
    margin-bottom: 6px;
    font-size: 12px;
    color: var(--apple-secondary);
  }

  .agent-confirm-head {
    margin-bottom: 6px;
  }

  .agent-confirm-name {
    display: flex;
    gap: 6px;
    align-items: center;
    font-weight: 500;
    color: var(--apple-text);

    .agent-confirm-count,
    .agent-confirm-no {
      font-size: 12px;
      font-weight: 400;
      color: var(--apple-tertiary);
    }

    .agent-chip {
      padding: 1px 9px;
      font-size: 12px;
      font-weight: 400;
      color: var(--apple-secondary);
      background: var(--apple-info-soft);
      border-radius: 999px;
    }
  }

  .agent-confirm-fields {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 3px 12px;
    margin: 6px 0 0;

    dt {
      font-size: 12px;
      color: var(--apple-secondary);
    }

    dd {
      margin: 0;
      color: var(--apple-text);
      word-break: break-word;
    }
  }

  .agent-confirm-list {
    padding: 0;
    margin: 0;
    list-style: none;

    > li + li {
      padding-top: 10px;
      margin-top: 10px;
      border-top: 1px solid var(--apple-separator);
    }
  }

  .agent-confirm-raw {
    margin-top: 10px;

    .agent-tool-summary {
      display: inline-flex;
      gap: 5px;
      align-items: center;
      padding: 2px 0;
      font-family: var(--apple-font);
      font-size: 12px;
      color: var(--apple-secondary);
      cursor: pointer;
      background: transparent;
      border: none;

      &:hover {
        color: var(--apple-text);
      }
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

  .agent-confirm-actions {
    display: flex;
    gap: 8px;
    margin-top: 12px;

    .agent-confirm-btn {
      height: 36px;
      padding: 0 16px;
      font-family: var(--apple-font);
      font-size: 13px;
      font-weight: 500;
      color: var(--apple-text);
      cursor: pointer;
      background: var(--apple-surface);
      border: 1px solid var(--apple-separator-strong);
      border-radius: 980px;
      transition: background-color 0.18s ease, border-color 0.18s ease;

      &:hover {
        background: var(--apple-bg);
      }

      &--primary {
        color: #fff;
        background: var(--apple-blue);
        border-color: var(--apple-blue);

        &:hover {
          background: var(--apple-blue-hover);
          border-color: var(--apple-blue-hover);
        }
      }
    }
  }

  .agent-confirm-wait {
    margin-top: 8px;
    font-size: 12px;
    color: var(--apple-secondary);
  }
}
</style>
