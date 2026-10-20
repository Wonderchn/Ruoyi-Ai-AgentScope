<!--
  花费信封余额提示：聊天输入框内「模型」旁边的只读事实。

  为什么需要：信封只增不减（历史缺陷）时，用户在撞到 BUDGET_EXCEEDED 之前
  完全看不到额度状况。这里把服务端 remainingCny 原样展示，**不推算、不缓存**。
  读取失败时整块不渲染（宁可不显示，也不显示假数字）。
-->
<script setup lang="ts">
import type { SpendBalance } from '@/api/ai/spend';
import { onMounted, ref } from 'vue';
import { fetchSpendBalance } from '@/api/ai/spend';
import { useUserStore } from '@/stores/modules/user';

const userStore = useUserStore();
const balance = ref<SpendBalance | null>(null);

function formatCny(value: number): string {
  return `¥${value.toFixed(2)}`;
}

onMounted(async () => {
  if (!userStore.token)
    return;
  balance.value = await fetchSpendBalance();
});
</script>

<template>
  <span
    v-if="balance && balance.enabled"
    class="spend-balance"
    data-testid="spend-balance"
    :title="`上限 ${formatCny(balance.capCny)} · 已占用 ${formatCny(balance.reservedCny)}`"
  >
    余额 {{ formatCny(balance.remainingCny) }}
  </span>
</template>

<style scoped lang="scss">
.spend-balance {
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  height: 24px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--apple-text-secondary, rgba(0, 0, 0, 0.55));
  white-space: nowrap;
  cursor: default;
}
</style>
