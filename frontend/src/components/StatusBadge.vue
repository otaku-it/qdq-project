<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const label = computed(() => ({
  RUNNING: '执行中',
  NEEDS_USER_ACTION: '等待确认',
  SUCCEEDED: '已完成',
  FAILED: '执行失败',
  PENDING: '待执行',
  WAITING_USER: '等待授权',
}[props.status] ?? props.status))
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">{{ label }}</span>
</template>
