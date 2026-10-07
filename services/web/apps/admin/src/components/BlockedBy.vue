<!--
  AI 管理域"端点尚未实现"的诚实标注组件。

  任务纪律（W3-5）：端点未实现的页面**不得造假成功**——页面照分母/契约开发，
  但必须把"为什么联调判据是 NOT_RUN"直接写进页面，而不是藏在报告里。
  统一用这一个组件，保证 testid 可被脚本统一识别：
  data-testid="blocked-<reason>"。
-->
<script setup lang="ts">
defineProps<{
  /** 机器可判的阻断原因：G-22 | EMBEDDED-REGISTRY | OTHER。 */
  reason: 'G-22' | 'EMBEDDED-REGISTRY' | 'OTHER';
  /** 给人看的一句话：哪一层没实现。 */
  detail: string;
}>();
</script>

<template>
  <ElAlert
    :data-testid="`blocked-${reason}`"
    type="warning"
    :closable="false"
    class="mb-4"
  >
    <template #title>
      本形态端点未实现，页面按分母/契约先行开发（联调判据 NOT_RUN）
    </template>
    {{ detail }}
  </ElAlert>
</template>
