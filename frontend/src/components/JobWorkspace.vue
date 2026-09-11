<script setup lang="ts">
import { Bot, Check, Circle, Clock3, FlaskConical, LoaderCircle, QrCode, RefreshCw, RotateCcw, ShieldCheck, TriangleAlert } from '@lucide/vue'
import type { DeliveryStatus, LauncherJob, StepStatus } from '../types'
import StatusBadge from './StatusBadge.vue'

const props = defineProps<{ job: LauncherJob; busy: boolean }>()
defineEmits<{ continue: []; retry: []; reset: [] }>()

const qrCells = Array.from({ length: 121 }, (_, index) => ((index * 17 + Math.floor(index / 11) * 7 + 3) % 5) < 2)

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}

function stepIcon(status: StepStatus) {
  return { PENDING: Circle, RUNNING: LoaderCircle, WAITING_USER: Clock3, SUCCEEDED: Check, FAILED: TriangleAlert }[status]
}

function deliveryLabel(status: DeliveryStatus) {
  return { PENDING: '待执行', PROVISIONING: '执行中', WAITING_AUTHORIZATION: '等待登录', READY: '已完成', FAILED: '执行失败' }[status]
}

function roleLabel() {
  return props.job.role === 'ADMIN' ? '企业管理员' : '普通用户'
}
</script>

<template>
  <section class="workspace job-workspace">
    <div class="section-heading job-heading"><div><p class="eyebrow">LAUNCH {{ job.id.slice(0, 8).toUpperCase() }}</p><h1>{{ job.tenantName }}</h1></div><div class="heading-actions"><StatusBadge :status="job.status" /><button class="icon-button" type="button" title="创建新的启动任务" @click="$emit('reset')"><RotateCcw :size="18" /></button></div></div>
    <div class="progress-block"><div class="progress-meta"><span>任务树进度</span><strong>{{ job.progress }}%</strong></div><div class="progress-track"><span :style="{ width: `${job.progress}%` }"></span></div><div class="job-facts"><span>身份 {{ roleLabel() }}</span><span>中台用户 {{ job.operatorName }}</span><span>飞书用户 {{ job.larkUser }}</span><span>任务 {{ job.selectedTasks.length }} 项</span><span>更新于 {{ formatTime(job.updatedAt) }}</span></div></div>
    <div v-if="job.status === 'NEEDS_USER_ACTION'" class="qr-login-banner">
      <div class="demo-qr" aria-label="飞书登录二维码"><span v-for="(filled, index) in qrCells" :key="index" :class="{ filled }"></span></div>
      <div class="qr-copy"><span class="banner-icon"><QrCode :size="22" /></span><div><strong>请登录飞书工作台</strong><p>{{ job.currentAction }}</p><small>二维码为 Demo 展示。生产环境应由后端生成一次性二维码并在扫码回调中恢复任务。</small></div></div>
      <button class="warning-button" type="button" :disabled="busy" @click="$emit('continue')"><Check :size="17" />{{ busy ? '确认中' : '已完成扫码登录' }}</button>
    </div>
    <div v-else-if="job.status === 'FAILED'" class="action-banner error-banner"><span class="banner-icon"><TriangleAlert :size="22" /></span><div><strong>任务树已暂停</strong><p>{{ job.currentAction }}</p></div><button class="danger-button" type="button" :disabled="busy" @click="$emit('retry')"><RefreshCw :size="17" />{{ busy ? '重试中' : '重试失败节点' }}</button></div>
    <div v-else-if="job.status === 'SUCCEEDED'" class="action-banner success-banner"><span class="banner-icon"><ShieldCheck :size="22" /></span><div><strong>启动任务已完成</strong><p>{{ job.role === 'ADMIN' ? '企业知识库同步和 Skills 预置结果已交付给租户。' : '个人知识库同步和豆包 Agent 初始化结果已交付给当前用户。' }}</p></div></div>
    <section class="capability-panel"><div class="panel-heading"><div><h2>任务交付</h2><span>{{ job.role === 'ADMIN' ? '企业级结果' : '个人级结果' }}</span></div></div><div class="capability-list"><div v-for="delivery in job.deliveries" :key="delivery.name" class="capability-row"><div><span class="scope-tag" :class="{ 'user-scope': delivery.scope === '个人级' }">{{ delivery.scope }}</span><strong>{{ delivery.name }}</strong><p>{{ delivery.description }}</p></div><span class="capability-status" :class="`capability-${delivery.status.toLowerCase()}`">{{ deliveryLabel(delivery.status) }}</span></div></div></section>
    <div class="execution-grid">
      <div class="steps-panel"><div class="panel-heading"><h2>执行任务树</h2><span>{{ job.steps.filter((step) => step.status === 'SUCCEEDED').length }}/{{ job.steps.length }}</span></div><ol class="step-list"><li v-for="(step, index) in job.steps" :key="step.id" :class="`step-${step.status.toLowerCase()}`"><span class="step-rail"><span class="step-marker"><component :is="stepIcon(step.status)" :size="17" :class="{ spinning: step.status === 'RUNNING' }" /></span><span v-if="index < job.steps.length - 1" class="rail-line"></span></span><span class="step-copy"><span class="step-title-row"><strong>{{ step.title }}</strong><span class="step-badges"><span v-if="step.executionMode" class="execution-badge" :class="`mode-${step.executionMode.toLowerCase()}`"><Bot v-if="step.executionMode === 'PLAYWRIGHT'" :size="12" /><FlaskConical v-else :size="12" />{{ step.executionMode === 'PLAYWRIGHT' ? (step.id === 'skills-upload' ? 'Playwright 真上传' : 'Playwright 真初始化') : 'Demo 模拟' }}</span><StatusBadge :status="step.status" /></span></span><small>{{ step.description }}</small><small v-if="step.resultMessage" class="step-result">{{ step.resultMessage }}</small></span></li></ol></div>
      <aside class="events-panel"><div class="panel-heading"><h2>运行记录</h2><span>最近 {{ job.events.length }} 条</span></div><div class="event-list"><div v-for="event in job.events" :key="`${event.timestamp}-${event.message}`" class="event-row"><span class="event-dot" :class="`event-${event.level}`"></span><div><p>{{ event.message }}</p><time>{{ formatTime(event.timestamp) }}</time></div></div></div></aside>
    </div>
  </section>
</template>
