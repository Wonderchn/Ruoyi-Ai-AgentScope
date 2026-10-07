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
        color: '#E6A23C',
        bgColor: 'rgba(230, 162, 60, 0.1)',
        text: '调用中',
      };
    case 'success':
      return {
        icon: 'CircleCheckFilled',
        color: '#67C23A',
        bgColor: 'rgba(103, 194, 58, 0.1)',
        text: '成功',
      };
    case 'error':
      return {
        icon: 'CircleCloseFilled',
        color: '#F56C6C',
        bgColor: 'rgba(245, 108, 108, 0.1)',
        text: '失败',
      };
    default:
      return {
        icon: 'QuestionFilled',
        color: '#909399',
        bgColor: 'rgba(144, 147, 153, 0.1)',
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
  background-color: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  margin: 8px 0;
  font-size: 13px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  transition: all 0.3s ease;

  &:hover {
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
  }

  &.expanded {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    cursor: pointer;
    user-select: none;
    transition: background-color 0.2s ease;

    &:hover {
      background-color: #f5f7fa;
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      min-width: 0;

      .tool-icon {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        border-radius: 6px;
        color: #ffffff;
        flex-shrink: 0;
      }

      .tool-name {
        font-weight: 500;
        color: #303133;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .status-tag {
        display: flex;
        align-items: center;
        gap: 4px;
        border-radius: 4px;
        padding: 2px 8px;
        font-size: 12px;
        flex-shrink: 0;

        .status-text {
          line-height: 1;
        }
      }
    }

    .header-right {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-shrink: 0;

      .call-time {
        font-size: 12px;
        color: #909399;
      }

      .expand-icon {
        color: #909399;
        transition: transform 0.3s ease;

        &.rotated {
          transform: rotate(180deg);
        }
      }
    }
  }

  .card-content {
    padding: 0 12px 12px;
    border-top: 1px solid #ebeef5;

    .result-section {
      margin-top: 12px;

      .section-label {
        font-size: 12px;
        color: #909399;
        margin-bottom: 8px;
        font-weight: 500;
      }

      .result-content {
        background-color: #f5f7fa;
        border-radius: 6px;
        padding: 10px 12px;
        max-height: 300px;
        overflow: auto;

        pre {
          margin: 0;
          font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
          font-size: 12px;
          line-height: 1.5;
          color: #303133;
          white-space: pre-wrap;
          word-break: break-word;
        }
      }
    }

    .pending-hint {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 16px;
      color: #E6A23C;

      .is-loading {
        animation: rotate 1s linear infinite;
      }
    }

    .no-result {
      text-align: center;
      padding: 16px;
      color: #909399;
      font-size: 12px;
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
</style>
