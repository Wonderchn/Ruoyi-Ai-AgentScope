<script setup lang="ts">
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { onBeforeUnmount, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
let observer: MutationObserver | null = null;
let raf = 0;

/**
 * 窄屏下表格会重排成两行卡片（见 apple-theme.css）；每格用表头文案作为
 * `data-label`，让"字段名 / 字段值"成对保留。纯展示层增强，不改业务数据。
 */
function decorateTables() {
  document.querySelectorAll<HTMLElement>('.el-table').forEach((table) => {
    const headers = Array.from(
      table.querySelectorAll<HTMLElement>('.el-table__header-wrapper thead th'),
    ).map(th => th.textContent?.trim() || '');

    table.querySelectorAll<HTMLElement>('.el-table__body tbody tr').forEach((row) => {
      Array.from(row.children).forEach((cell, index) => {
        const label = headers[index] || '';
        if (label && cell.getAttribute('data-label') !== label)
          cell.setAttribute('data-label', label);
      });
    });
  });
}

function scheduleDecorate() {
  if (raf)
    return;
  raf = window.requestAnimationFrame(() => {
    raf = 0;
    decorateTables();
  });
}

onMounted(() => {
  decorateTables();
  observer = new MutationObserver(scheduleDecorate);
  observer.observe(document.body, { childList: true, subtree: true });
});

onBeforeUnmount(() => {
  observer?.disconnect();
  if (raf)
    window.cancelAnimationFrame(raf);
});

watch(() => route.fullPath, () => {
  window.setTimeout(decorateTables, 200);
});
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <RouterView />
  </ElConfigProvider>
</template>
