<!--
  Agent 一轮对话的**过程轨迹卡**（F10 op1 / op5 的渲染面）。

  一轮用户↔助手收进一张卡：轮次头（TURN n · 时刻 · 总耗时）+ 各通道轨迹行。
  通道：reasoning / tool / answer / hint / confirm / batch / error。
  投影全部来自 `agentTimeline.buildTimelineRows`（纯函数，服务端字段照抄 —— 前端不推断状态与耗时）。

  - **工具行**交给 `AgentToolCard`（状态芯片/结果展开/耗时；调用参数只在确认卡）；
  - **确认行**交给 `AgentConfirmCard`（整卡一次裁决）；
  - **批头行**（同批多工具）显示"同批 N 个工具 · 并行"与包络耗时；
  - reasoning 行可折叠（折叠态给单行摘要），流式中自动展开。
-->
<script setup lang="ts">
import type { TraceRow } from '../agentTimeline';
import type { AgentBlock, AgentTurn } from '../types';
import { computed } from 'vue';
import { formatDuration, peekThinking } from '../agentTimeline';
import AgentConfirmCard from './AgentConfirmCard.vue';
import AgentToolCard from './AgentToolCard.vue';

const props = defineProps<{
  turn: AgentTurn;
  /** 轨迹行（由页面用 `buildTimelineRows` 产出，传进来只为渲染 —— 组件不做投影）。 */
  rows: TraceRow[];
}>();

const emit = defineEmits<{
  (event: 'toggle', payload: { messageId: string; blockId: number }): void;
  (event: 'resolve', payload: { approved: boolean }): void;
}>();

const visibleRows = computed(() => props.rows.filter(row => row.channel !== 'user'));

/** 轮次头上的一次性总耗时：流式中不显示，收尾后才亮（一问多答按段累加）。 */
const streaming = computed(() => props.turn.assistants.some(assistant => assistant.status === 'streaming'));
const totalMs = computed(() => props.turn.assistants.reduce((sum, assistant) => sum + (assistant.elapsedMs ?? 0), 0));
const elapsedText = computed(() => (streaming.value ? '' : formatDuration(totalMs.value || undefined)));
const headTs = computed(() => visibleRows.value[0]?.ts ?? '');

function reasoningOpen(row: TraceRow): boolean {
  return Boolean(row.block?.open) || Boolean(row.streaming);
}

function toggleRow(row: TraceRow) {
  if (row.messageId)
    emit('toggle', { messageId: row.messageId, blockId: row.block?.id ?? -1 });
}

function isTool(row: TraceRow): row is TraceRow & { block: AgentBlock } {
  return row.channel === 'tool' && row.block !== undefined;
}
</script>

<template>
  <section class="agent-turn" data-testid="agent-turn">
    <header class="agent-turn-head">
      <span class="agent-turn-no">TURN {{ turn.index }}</span>
      <span v-if="headTs" class="agent-turn-ts">{{ headTs }}</span>
      <span v-if="elapsedText" class="agent-turn-dur" data-testid="agent-turn-duration"> · {{ elapsedText }}</span>
    </header>

    <div
      v-for="row in visibleRows"
      :key="row.key"
      class="agent-row"
      :data-channel="row.channel"
      :data-streaming="row.streaming || row.block?.status === 'running' ? 'true' : 'false'"
      data-testid="agent-row"
    >
      <!-- 批头行：仅说明同批规模与包络耗时，正文在各工具行 -->
      <template v-if="row.channel === 'batch'">
        <div class="agent-row-meta">
          <span class="agent-batch-note">
            同批 {{ row.batchSize }} 个工具{{ row.parallel ? ' · 并行' : '' }}
          </span>
          <span v-if="row.durationMs != null" class="agent-row-dur"> · {{ formatDuration(row.durationMs) }}</span>
          <span v-if="row.ts" class="agent-row-ts">{{ row.ts }}</span>
        </div>
      </template>

      <!-- 工具行 -->
      <template v-else-if="isTool(row)">
        <div class="agent-row-meta">
          <span class="agent-channel">tool</span>
          <span v-if="row.ts" class="agent-row-ts">{{ row.ts }}</span>
        </div>
        <AgentToolCard
          :block="row.block"
          :message-id="row.messageId"
          :duration-ms="row.durationMs"
          @toggle="emit('toggle', $event)"
        />
      </template>

      <!-- 确认行 -->
      <template v-else-if="row.channel === 'confirm' && row.block">
        <div class="agent-row-meta">
          <span class="agent-channel">confirm</span>
          <span v-if="row.ts" class="agent-row-ts">{{ row.ts }}</span>
        </div>
        <AgentConfirmCard
          :block="row.block"
          :message-id="row.messageId"
          :outcomes="row.outcomes"
          @resolve="emit('resolve', { approved: $event })"
          @toggle="emit('toggle', $event)"
        />
      </template>

      <!-- 思考行：可折叠，流式中强制展开 -->
      <template v-else-if="row.channel === 'reasoning'">
        <button
          type="button"
          class="agent-reasoning-toggle"
          :aria-expanded="reasoningOpen(row)"
          data-testid="agent-reasoning-toggle"
          @click="toggleRow(row)"
        >
          <span class="agent-caret">{{ reasoningOpen(row) ? '▾' : '▸' }}</span>
          <span class="agent-reasoning-peek">{{ peekThinking(row.block?.text ?? '') }}</span>
          <span v-if="row.durationMs != null" class="agent-row-dur"> · 生成 {{ formatDuration(row.durationMs) }}</span>
        </button>
        <pre v-if="reasoningOpen(row)" class="agent-reasoning-body" data-testid="agent-reasoning-body">{{ row.block?.text ?? '' }}</pre>
      </template>

      <!-- 正文/提示/错误行 -->
      <template v-else>
        <div class="agent-row-meta">
          <span class="agent-channel">{{ row.channel }}</span>
          <span v-if="row.durationMs != null" class="agent-row-dur"> · 生成 {{ formatDuration(row.durationMs) }}</span>
          <span v-if="row.ts" class="agent-row-ts">{{ row.ts }}</span>
        </div>
        <div class="agent-row-text" :data-streaming="row.streaming ? 'true' : 'false'">
          {{ row.text }}
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped lang="scss">
.agent-turn {
  margin: 6px 0 10px;

  .agent-turn-head {
    display: flex;
    align-items: center;
    gap: 8px;
    color: #909399;
    font-size: 12px;

    .agent-turn-no {
      font-weight: 600;
      letter-spacing: 0.4px;
    }
  }

  .agent-row {
    margin-top: 6px;

    .agent-row-meta {
      display: flex;
      align-items: center;
      gap: 8px;
      color: #909399;
      font-size: 12px;

      .agent-channel {
        color: #a3a6ad;
      }

      .agent-row-ts {
        margin-left: auto;
        font-variant-numeric: tabular-nums;
      }
    }

    .agent-batch-note {
      color: #909399;
    }

    .agent-row-dur {
      color: #909399;
    }

    .agent-row-text {
      margin-top: 2px;
      color: #303133;
      font-size: 13px;
      white-space: pre-wrap;
      word-break: break-word;

      &[data-streaming='true'] {
        color: #606266;
      }
    }

    .agent-reasoning-toggle {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 0;
      border: none;
      background: transparent;
      color: #909399;
      font-size: 12px;
      text-align: left;
      cursor: pointer;

      .agent-reasoning-peek {
        max-width: 520px;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
      }
    }

    .agent-reasoning-body {
      margin: 4px 0 0;
      padding: 8px 10px;
      border-radius: 6px;
      background: #f7f8fa;
      color: #606266;
      font-family: inherit;
      font-size: 12px;
      line-height: 1.6;
      white-space: pre-wrap;
      word-break: break-word;
      max-height: 240px;
      overflow: auto;
    }
  }
}
</style>
