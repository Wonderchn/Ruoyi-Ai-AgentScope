<script setup lang="ts">
import * as echarts from 'echarts';
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { parseEChartsOption } from '@/utils/echartsOption';

// 通过 selfProps 获取所有自定义属性
const props = defineProps({
  selfProps: {
    type: Object,
    default: () => ({}),
  },
});

const refEle = ref<HTMLElement>();
let myChart: echarts.ECharts | null = null;
let retryCount = 0;
const maxRetries = 3;

// 使用计算属性保持响应性
const code = () => props.selfProps?.code ?? '';
const width = () => props.selfProps?.width ?? '100%';
const height = () => props.selfProps?.height ?? '400px';
const theme = () => props.selfProps?.theme ?? 'dark';
const title = () => props.selfProps?.title ?? '';

// 渲染图表
function renderChart() {
  if (!refEle.value) {
    console.log('[EchartsRenderer] refEle 不存在');
    return;
  }

  // 检查 DOM 是否有实际尺寸
  if (refEle.value.clientWidth === 0 || refEle.value.clientHeight === 0) {
    if (retryCount < maxRetries) {
      retryCount++;
      console.warn(`[EchartsRenderer] 容器尺寸为 0 (${refEle.value.clientWidth} x ${refEle.value.clientHeight})，重试 ${retryCount}/${maxRetries}`);
      setTimeout(() => {
        renderChart();
      }, 100);
    }
    else {
      console.error('[EchartsRenderer] 容器无法获取有效尺寸，放弃');
      if (refEle.value) {
        refEle.value.textContent = '容器尺寸无效 (0x0)';
      }
    }
    return;
  }

  // 重置重试计数
  retryCount = 0;

  const configData = code();

  if (!configData) {
    myChart?.dispose();
    myChart = null;
    refEle.value.textContent = '';
    return;
  }

  const option = parseEChartsOption(configData);
  console.log('[EchartsRenderer] 解析结果:', option ? '成功' : '失败');

  if (!option) {
    myChart?.dispose();
    myChart = null;
    refEle.value.textContent = '配置解析失败：请提供包含 series 的合法 ECharts JSON';
    return;
  }

  try {
    console.log('[EchartsRenderer] 初始化 ECharts...');
    if (!myChart) {
      refEle.value.textContent = '';
      myChart = echarts.init(refEle.value, theme());
      console.log('[EchartsRenderer] ECharts 初始化完成');
    }
    myChart.setOption(option, { notMerge: true });
    console.log('[EchartsRenderer] 配置设置成功');
  }
  catch (error) {
    console.error('[EchartsRenderer] 图表渲染失败:', error);
    myChart?.dispose();
    myChart = null;
    refEle.value.textContent = '图表渲染失败，请检查图表类型与配置';
  }
}

// 窗口resize处理
function handleResize() {
  myChart?.resize();
}

// 监听容器大小变化
const resizeObserver = new ResizeObserver(() => {
  myChart?.resize();
});

// 生命周期
onMounted(() => {
  // 重置重试计数
  retryCount = 0;

  // 使用 nextTick 确保 DOM 已经完全渲染
  nextTick(() => {
    // 再加一个小延迟，确保样式已应用且容器有实际尺寸
    setTimeout(() => {
      renderChart();
      if (refEle.value) {
        resizeObserver.observe(refEle.value);
      }
      window.addEventListener('resize', handleResize);
    }, 50);
  });
});

// 监听props变化
watch(
  () => props.selfProps,
  () => {
    // 延迟执行，确保 DOM 已经准备好
    nextTick(() => {
      setTimeout(() => {
        renderChart();
      }, 50);
    });
  },
  { deep: true },
);

// 清理资源
onUnmounted(() => {
  resizeObserver.disconnect();
  window.removeEventListener('resize', handleResize);
  myChart?.dispose();
  myChart = null;
});
</script>

<template>
  <div class="echarts-container">
    <!-- 副标题（可选） -->
    <div v-if="title()" class="echarts-title">
      {{ title() }}
    </div>
    <!-- 图表容器 -->
    <div
      ref="refEle"
      :style="{
        width: width(),
        height: height(),
        minWidth: '800px',
        minHeight: '300px',
        borderRadius: '8px',
        overflow: 'hidden',
        backgroundColor: theme() === 'light' ? '#fff' : '#1a1a1a',
        boxSizing: 'border-box',
      }"
    />
  </div>
</template>

<style scoped lang="scss">
.echarts-container {
  display: block;
  width: 100%;
  overflow: visible;
  box-sizing: border-box;
  transform: scale(0.62);
  transform-origin: top left;
  margin-bottom: -228px;

  .echarts-title {
    font-size: 14px;
    color: rgba(0, 0, 0, 0.6);
    font-weight: 500;
    margin-bottom: 8px;
  }
}
</style>
