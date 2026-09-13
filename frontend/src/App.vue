<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Activity, Blocks, Bot, Boxes, CircleHelp, CloudCog, FileStack, LayoutDashboard, LogOut, Settings2, UserRound } from '@lucide/vue'
import { launcherApi } from './api'
import LoginPage from './components/LoginPage.vue'
import SetupForm from './components/SetupForm.vue'
import JobWorkspace from './components/JobWorkspace.vue'
import FeishuMigrationConfig from './components/FeishuMigrationConfig.vue'
import type { Blueprint, LauncherJob, LoginSession } from './types'

const blueprint = ref<Blueprint | null>(null)
const job = ref<LauncherJob | null>(null)
const session = ref<LoginSession | null>(readSession())
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const activeView = ref<'launcher' | 'migration-config'>('launcher')
let pollTimer: number | undefined

const sidebarTasks = computed(() => blueprint.value?.tasks ?? [])

onMounted(loadBlueprint)
onBeforeUnmount(stopPolling)

async function loadBlueprint() {
  try {
    blueprint.value = await launcherApi.getBlueprint()
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    loading.value = false
  }
}

async function createJob(payload: { tenantName: string; operatorName: string; role: 'ADMIN' | 'USER'; larkUser: string; selectedTasks: string[]; selectedSkills: string[]; simulateFailure: boolean }) {
  busy.value = true
  error.value = ''
  try {
    job.value = await launcherApi.createJob(payload)
    startPolling()
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    busy.value = false
  }
}

function login(nextSession: LoginSession) {
  session.value = nextSession
  activeView.value = 'launcher'
  sessionStorage.setItem('zhiling-launcher-session', JSON.stringify(nextSession))
  error.value = ''
}

function logout() {
  stopPolling()
  job.value = null
  session.value = null
  error.value = ''
  sessionStorage.removeItem('zhiling-launcher-session')
}

async function continueJob() {
  if (!job.value) return
  busy.value = true
  error.value = ''
  try {
    job.value = await launcherApi.continueJob(job.value.id)
    startPolling()
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    busy.value = false
  }
}

async function retryJob() {
  if (!job.value) return
  busy.value = true
  error.value = ''
  try {
    job.value = await launcherApi.retryJob(job.value.id)
    startPolling()
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    busy.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(refreshJob, 500)
}

async function refreshJob() {
  if (!job.value || job.value.status !== 'RUNNING') {
    stopPolling()
    return
  }
  try {
    job.value = await launcherApi.getJob(job.value.id)
    if (job.value.status !== 'RUNNING') stopPolling()
  } catch (cause) {
    error.value = messageFrom(cause)
    stopPolling()
  }
}

function reset() {
  stopPolling()
  job.value = null
  error.value = ''
}

function showLauncher() {
  activeView.value = 'launcher'
  error.value = ''
}

function showMigrationConfig() {
  activeView.value = 'migration-config'
  error.value = ''
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function messageFrom(cause: unknown) {
  return cause instanceof Error ? cause.message : '发生未知错误'
}

function readSession(): LoginSession | null {
  try {
    const value = sessionStorage.getItem('zhiling-launcher-session')
    if (!value) return null
    const parsed = JSON.parse(value) as LoginSession
    if (!parsed.tenantName || !parsed.operatorName || !parsed.larkUser || !['ADMIN', 'USER'].includes(parsed.role)) return null
    return parsed
  } catch {
    return null
  }
}
</script>

<template>
  <LoginPage v-if="!session" @login="login" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">Z</span><span><strong>智灵领航</strong><small>FEISHU LAUNCHER</small></span></div>
      <nav class="primary-nav" aria-label="主导航">
        <a :class="{ active: activeView === 'launcher' }" href="#" @click.prevent="showLauncher"><LayoutDashboard :size="18" /><span>启动任务</span></a>
        <a v-if="session.role === 'ADMIN'" :class="{ active: activeView === 'migration-config' }" href="#" @click.prevent="showMigrationConfig"><Boxes :size="18" /><span>知识库同步</span></a>
        <a href="#" @click.prevent><Blocks :size="18" /><span>Agent Skills</span></a>
        <a href="#" @click.prevent><Activity :size="18" /><span>运行记录</span></a>
      </nav>
      <div class="stage-overview"><p>可选任务</p><div v-for="(task, index) in sidebarTasks" :key="task.id" class="stage-line"><span>{{ String(index + 1).padStart(2, '0') }}</span><small>{{ task.name }}</small></div></div>
      <nav class="secondary-nav" aria-label="辅助导航">
        <a href="#" @click.prevent><Settings2 :size="18" /><span>环境设置</span></a>
        <a href="#" @click.prevent><CircleHelp :size="18" /><span>诊断帮助</span></a>
      </nav>
    </aside>
    <main class="main-area">
      <header class="topbar"><div class="breadcrumb"><span>智灵中台</span><span>/</span><strong>启动器</strong></div><div class="topbar-actions"><div class="environment-state"><span class="live-dot"></span>Demo 环境<span class="divider"></span><CloudCog :size="17" />API 已连接</div><span class="topbar-account"><UserRound :size="15" /><span><strong>{{ session.operatorName }}</strong><small>{{ session.role === 'ADMIN' ? '企业管理员' : '普通用户' }}</small></span></span><button class="topbar-logout" type="button" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="17" /></button></div></header>
      <div v-if="error" class="global-error" role="alert"><CircleHelp :size="18" /><span>{{ error }}</span><button type="button" aria-label="关闭" @click="error = ''">×</button></div>
      <div v-if="loading" class="loading-state"><Bot :size="32" /><span>正在加载启动器任务目录</span></div>
      <FeishuMigrationConfig v-else-if="activeView === 'migration-config' && session.role === 'ADMIN'" :session="session" />
      <JobWorkspace v-else-if="job" :job="job" :busy="busy" @continue="continueJob" @retry="retryJob" @reset="reset" />
      <SetupForm v-else-if="blueprint" :blueprint="blueprint" :session="session" :submitting="busy" @submit="createJob" />
      <footer class="app-footer"><span><FileStack :size="15" /> 钉钉知识库适配器 · 豆包 Skills Playwright Worker</span><span>Vue 3 + Spring Boot</span></footer>
    </main>
  </div>
</template>
