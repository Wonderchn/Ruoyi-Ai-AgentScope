<!-- F03 历史消息详情：深度思考 / 引用来源 / 检索片段 / 推荐问题 / 用量 -->
<script setup lang="ts">
import type { ChatMessageVo } from '@/api/chat/types';
import { computed } from 'vue';
import { hasDetails, toMessageDetails } from '@/api/chat/details';

const props = defineProps<{
  message: ChatMessageVo;
}>();

const details = computed(() => toMessageDetails(props.message));
const show = computed(() => hasDetails(details.value));

/** 检索片段正文默认折叠（片段可能很长，全展开会把消息挤没）。 */
const expandedChunks = ref(false);
</script>

<template>
  <div v-if="show" class="message-details">
    <!-- 深度思考：用后端结构化的 thinking_content，而不是从正文里正则抠 <think> -->
    <div v-if="details.thinking" class="detail-block">
      <div class="detail-title">
        深度思考
        <span v-if="details.thinkingDuration" class="detail-meta">{{ details.thinkingDuration }}</span>
      </div>
      <pre class="detail-thinking">{{ details.thinking }}</pre>
    </div>

    <!-- 引用来源 -->
    <div v-if="details.citations.length" class="detail-block">
      <div class="detail-title">
        引用来源（{{ details.citations.length }}）
      </div>
      <ul class="detail-list">
        <li v-for="(citation, index) in details.citations" :key="index">
          <span class="citation-title">{{ citation.title }}</span>
          <span v-if="citation.detail" class="detail-meta">{{ citation.detail }}</span>
        </li>
      </ul>
    </div>
    <!-- 解析失败时保留原文：坏数据仍要给人看，而不是假装没有引用 -->
    <div v-else-if="details.citationsRaw" class="detail-block">
      <div class="detail-title">
        引用来源（原始文本，未能解析为 JSON）
      </div>
      <pre class="detail-raw">{{ details.citationsRaw }}</pre>
    </div>

    <!-- 检索片段 -->
    <div v-if="details.chunks.length" class="detail-block">
      <div class="detail-title">
        检索片段（{{ details.chunks.length }}）
        <el-button link size="small" @click="expandedChunks = !expandedChunks">
          {{ expandedChunks ? '收起' : '展开' }}
        </el-button>
      </div>
      <ul class="detail-list">
        <li v-for="(chunk, index) in details.chunks" :key="index">
          <span class="citation-title">{{ chunk.title }}</span>
          <span v-if="chunk.detail" class="detail-meta">{{ chunk.detail }}</span>
          <div v-if="expandedChunks && chunk.content" class="detail-chunk-content">
            {{ chunk.content }}
          </div>
        </li>
      </ul>
    </div>
    <div v-else-if="details.chunksRaw" class="detail-block">
      <div class="detail-title">
        检索片段（原始文本，未能解析为 JSON）
      </div>
      <pre class="detail-raw">{{ details.chunksRaw }}</pre>
    </div>

    <!-- 推荐问题 -->
    <div v-if="details.recommended.length" class="detail-block">
      <div class="detail-title">
        推荐问题
      </div>
      <div class="detail-recommended">
        <el-tag v-for="(question, index) in details.recommended" :key="index" size="small" type="info">
          {{ question }}
        </el-tag>
      </div>
    </div>
    <div v-else-if="details.recommendedRaw" class="detail-block">
      <div class="detail-title">
        推荐问题（原始文本，未能解析为 JSON）
      </div>
      <pre class="detail-raw">{{ details.recommendedRaw }}</pre>
    </div>

    <!-- 用量：模型名与 tokens（两者都可能 NULL，缺失的部分不占位） -->
    <div v-if="details.usage" class="detail-usage">
      {{ details.usage }}
    </div>
  </div>
</template>

<style scoped lang="scss">
.message-details {
  margin-top: 8px;
  padding: 8px 10px;
  font-size: 12px;
  color: rgb(0 0 0 / 65%);
  background-color: rgb(0 0 0 / 2%);
  border-radius: 8px;
}

.detail-block + .detail-block {
  margin-top: 8px;
}

.detail-title {
  display: flex;
  gap: 8px;
  align-items: center;
  font-weight: 600;
  color: rgb(0 0 0 / 78%);
}

.detail-meta {
  font-weight: 400;
  color: rgb(0 0 0 / 45%);
}

.detail-thinking,
.detail-raw {
  max-height: 220px;
  margin: 4px 0 0;
  padding: 6px 8px;
  overflow: auto;
  font-family: inherit;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: rgb(255 255 255 / 70%);
  border-radius: 6px;
}

.detail-list {
  margin: 4px 0 0;
  padding-left: 18px;

  li + li {
    margin-top: 4px;
  }
}

.citation-title {
  color: rgb(0 0 0 / 78%);
}

.detail-chunk-content {
  margin-top: 4px;
  padding: 6px 8px;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: rgb(255 255 255 / 70%);
  border-radius: 6px;
}

.detail-recommended {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.detail-usage {
  margin-top: 8px;
  color: rgb(0 0 0 / 45%);
}
</style>
