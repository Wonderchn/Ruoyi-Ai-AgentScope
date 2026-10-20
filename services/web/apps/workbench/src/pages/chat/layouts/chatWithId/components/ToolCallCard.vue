<script setup lang="ts">
import type { ToolCallInfo } from '@/pages/chat/layouts/chatWithId/types';

const props = defineProps<{
  toolInfo: ToolCallInfo;
}>();

const isExpanded = ref(false);

// 状态图标和颜色映射
const statusConfig = computed(() => {
  switch (props.toolInfo.status) {
    case 'pending':
      return {
        icon: 'Loading',
        color: 'var(--apple-warning)',
        bgColor: 'var(--apple-warning-soft)',
        text: '调用中',
      };
    case 'success':
      return {
        icon: 'CircleCheckFilled',
        color: 'var(--apple-success)',
        bgColor: 'var(--apple-success-soft)',
        text: '成功',
      };
    case 'error':
      return {
        icon: 'CircleCloseFilled',
        color: 'var(--apple-danger)',
        bgColor: 'var(--apple-danger-soft)',
        text: '失败',
      };
    default:
      return {
        icon: 'QuestionFilled',
        color: 'var(--apple-secondary)',
        bgColor: 'var(--apple-info-soft)',
        text: '未知',
      };
  }
});

// 格式化时间
const formattedTime = computed(() => {
  if (!props.toolInfo.timestamp)
    return '';
  const date = new Date(props.toolInfo.timestamp);
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
});

// 格式化结果内容（用于展示）
const formattedResult = computed(() => {
  if (!props.toolInfo.result)
    return null;
  try {
    // 如果结果是字符串，尝试解析
    if (typeof props.toolInfo.result === 'string') {
      return props.toolInfo.result;
    }
    return JSON.stringify(props.toolInfo.result, null, 2);
  }
  catch {
    return String(props.toolInfo.result);
  }
});
</script>

<template>
  <div class="tool-call-card" :class="{ expanded: isExpanded }">
    <!-- 卡片头部 - 始终显示 -->
    <div class="card-header" @click="isExpanded = !isExpanded">
      <div class="header-left">
        <!-- 工具图标 -->
        <div class="tool-icon">
          <el-icon :size="16">
            <Tools />
          </el-icon>
        </div>
        <!-- 工具名称 -->
        <span class="tool-name">{{ toolInfo.name || '工具调用' }}</span>
        <!-- 状态标签 -->
        <el-tag
          :style="{
            color: statusConfig.color,
            backgroundColor: statusConfig.bgColor,
            borderColor: statusConfig.color,
          }"
          size="small"
          class="status-tag"
        >
          <el-icon v-if="toolInfo.status === 'pending'" class="is-loading" :size="12">
            <Loading />
          </el-icon>
          <el-icon v-else :size="12">
            <component :is="statusConfig.icon" />
          </el-icon>
          <span class="status-text">{{ statusConfig.text }}</span>
        </el-tag>
      </div>
      <div class="header-right">
        <!-- 调用时间 -->
        <span v-if="formattedTime" class="call-time">{{ formattedTime }}</span>
        <!-- 展开/收起图标 -->
        <el-icon class="expand-icon" :class="{ rotated: isExpanded }">
          <ArrowDown />
        </el-icon>
      </div>
    </div>

    <!-- 展开内容 -->
    <el-collapse-transition>
      <div v-show="isExpanded" class="card-content">
        <!-- 结果详情 -->
        <div v-if="formattedResult" class="result-section">
          <div class="section-label">
            执行结果
          </div>
          <div class="result-content">
            <pre>{{ formattedResult }}</pre>
          </div>
        </div>
        <!-- 无结果时显示 -->
        <div v-else-if="toolInfo.status === 'pending'" class="pending-hint">
          <el-icon class="is-loading">
            <Loading />
          </el-icon>
          <span>正在执行中...</span>
        </div>
        <div v-else class="no-result">
          <span>暂无返回结果</span>
        </div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<style scoped lang="scss">
.tool-call-card {
  margin: 8px 0;
  overflow: hidden;
  font-size: 13px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 14px;
  transition: border-color 0.18s ease;

  &:hover {
    border-color: var(--apple-separator-strong);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 11px 14px;
    cursor: pointer;
    user-select: none;
    transition: background-color 0.18s ease;

    &:hover {
      background: var(--apple-bg);
    }

    .header-left {
      display: flex;
      flex: 1;
      gap: 9px;
      align-items: center;
      min-width: 0;

      .tool-icon {
        display: flex;
        flex-shrink: 0;
        align-items: center;
        justify-content: center;
        width: 28px;
        height: 28px;
        color: var(--apple-blue);
        background: var(--apple-blue-soft);
        border-radius: 8px;
      }

      .tool-name {
        overflow: hidden;
        font-weight: 500;
        color: var(--apple-text);
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .status-tag {
        display: flex;
        flex-shrink: 0;
        gap: 4px;
        align-items: center;
        padding: 2px 9px;
        font-size: 12px;
        border: 0;
        border-radius: 999px;

        .status-text {
          line-height: 1;
        }
      }
    }

    .header-right {
      display: flex;
      flex-shrink: 0;
      gap: 8px;
      align-items: center;

      .call-time {
        font-size: 12px;
        font-variant-numeric: tabular-nums;
        color: var(--apple-tertiary);
      }

      .expand-icon {
        color: var(--apple-tertiary);
        transition: transform 0.2s ease;

        &.rotated {
          transform: rotate(180deg);
        }
      }
    }
  }

  .card-content {
    padding: 0 14px 14px;
    border-top: 1px solid var(--apple-separator);

    .result-section {
      margin-top: 12px;

      .section-label {
        margin-bottom: 8px;
        font-size: 12px;
        font-weight: 500;
        color: var(--apple-secondary);
      }

      .result-content {
        max-height: 300px;
        padding: 12px 14px;
        overflow: auto;
        background: var(--apple-bg);
        border-radius: 12px;

        pre {
          margin: 0;
          font-family: var(--apple-font-mono);
          font-size: 12px;
          line-height: 1.6;
          color: var(--apple-text);
          white-space: pre-wrap;
          word-break: break-word;
        }
      }
    }

    .pending-hint {
      display: flex;
      gap: 8px;
      align-items: center;
      justify-content: center;
      padding: 16px;
      color: var(--apple-warning);

      .is-loading {
        animation: rotate 1s linear infinite;
      }
    }

    .no-result {
      padding: 16px;
      font-size: 12px;
      color: var(--apple-secondary);
      text-align: center;
    }
  }
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .tool-call-card,
  .tool-call-card .card-header,
  .tool-call-card .expand-icon {
    transition: none;
  }

  .tool-call-card .pending-hint .is-loading {
    animation: none;
  }
}
</style>
