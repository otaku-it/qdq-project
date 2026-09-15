<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Building2, ChevronDown, ChevronRight, ExternalLink, File, FileText, Folder, RefreshCw, Search, UserRound, X } from '@lucide/vue'
import { launcherApi } from '../api'
import type { KnowledgeDocList, KnowledgeDocNode, KnowledgeWorkspace, LoginSession } from '../types'

const props = defineProps<{ session: LoginSession }>()
const username = ref(props.session.operatorName)
const filename = ref('')
const data = ref<KnowledgeDocList | null>(null)
const loading = ref(false)
const error = ref('')
const expanded = ref(new Set<string>())

const workspaces = computed(() => {
  if (!data.value) return []
  const values: KnowledgeWorkspace[] = []
  if (data.value.personalDocs) values.push(data.value.personalDocs)
  values.push(...data.value.enterpriseDocs)
  return values
})

onMounted(load)

async function load() {
  if (!username.value.trim()) { error.value = '请输入钉钉用户名'; return }
  loading.value = true
  error.value = ''
  try {
    data.value = await launcherApi.getKnowledgeDocs(username.value.trim(), filename.value)
    expanded.value = new Set(workspaces.value.flatMap((workspace) => [workspace.workspaceId, ...workspace.children.filter((node) => node.type === 'FOLDER').map((node) => node.nodeId)]))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载钉钉知识库失败'
  } finally {
    loading.value = false
  }
}

function clearFilter() {
  filename.value = ''
  load()
}

function toggle(id: string) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
}

function formatBytes(size: number | null) {
  if (!size) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

function countFiles(nodes: KnowledgeDocNode[]): number {
  return nodes.reduce((total, node) => total + (node.type === 'FILE' ? 1 : 0) + countFiles(node.children), 0)
}

function visibleNodes(nodes: KnowledgeDocNode[], depth = 0): { node: KnowledgeDocNode; depth: number }[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...(node.type === 'FOLDER' && expanded.value.has(node.nodeId) ? visibleNodes(node.children, depth + 1) : []),
  ])
}
</script>

<template>
  <section class="workspace knowledge-workspace">
    <div class="section-heading">
      <div><p class="eyebrow">DINGTALK DOCUMENTS</p><h1>知识库同步管理</h1><p class="section-subtitle">查看当前钉钉用户可访问的个人与企业知识库文档。</p></div>
      <button class="secondary-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ spinning: loading }" />刷新</button>
    </div>

    <form class="knowledge-query" @submit.prevent="load">
      <label><span>钉钉用户名</span><div class="input-shell"><UserRound :size="16" /><input v-model="username" maxlength="64" placeholder="请输入钉钉姓名或花名" /></div></label>
      <label><span>文件名称</span><div class="input-shell"><Search :size="16" /><input v-model="filename" maxlength="128" placeholder="可选，按文件名筛选" /><button v-if="filename" type="button" title="清除筛选" @click="clearFilter"><X :size="14" /></button></div></label>
      <button class="primary-button" type="submit" :disabled="loading"><Search :size="16" />{{ loading ? '查询中' : '查询文档' }}</button>
    </form>

    <div v-if="error" class="knowledge-error" role="alert"><span>{{ error }}</span><button type="button" aria-label="关闭" @click="error = ''">×</button></div>
    <div v-if="data" class="knowledge-summary">
      <div><span class="summary-icon"><FileText :size="18" /></span><span><small>文档总数</small><strong>{{ data.totalDocCount }}</strong></span></div>
      <div><span class="summary-icon enterprise"><Building2 :size="18" /></span><span><small>企业知识库</small><strong>{{ data.enterpriseDocs.length }}</strong></span></div>
      <div><span class="summary-icon personal"><UserRound :size="18" /></span><span><small>个人知识库</small><strong>{{ data.personalDocs ? 1 : 0 }}</strong></span></div>
      <div class="summary-user"><img v-if="data.user.avatar" :src="data.user.avatar" alt="" /><span v-else class="summary-avatar"><UserRound :size="16" /></span><span><small>当前钉钉用户</small><strong>{{ data.user.name }}</strong></span></div>
    </div>

    <div v-if="loading && !data" class="knowledge-loading"><RefreshCw :size="22" class="spinning" /><span>正在读取钉钉知识库目录</span></div>
    <div v-else-if="data && !workspaces.length" class="knowledge-empty">当前用户没有可访问的知识库。</div>
    <div v-else class="workspace-list">
      <section v-for="workspace in workspaces" :key="workspace.workspaceId" class="knowledge-space">
        <header class="space-header">
          <button class="tree-toggle" type="button" :aria-label="expanded.has(workspace.workspaceId) ? '收起知识库' : '展开知识库'" @click="toggle(workspace.workspaceId)"><ChevronDown v-if="expanded.has(workspace.workspaceId)" :size="17" /><ChevronRight v-else :size="17" /></button>
          <span class="space-icon" :class="{ personal: workspace.type === 'PERSONAL' }"><UserRound v-if="workspace.type === 'PERSONAL'" :size="18" /><Building2 v-else :size="18" /></span>
          <div class="space-title"><strong>{{ workspace.name }}</strong><p>{{ workspace.description || (workspace.type === 'PERSONAL' ? '个人知识库' : '企业知识库') }}</p></div>
          <span class="space-meta">{{ countFiles(workspace.children) }} 个文件</span>
          <span class="space-role">{{ workspace.permissionRole || '-' }}</span>
          <a v-if="workspace.url" class="open-link" :href="workspace.url" target="_blank" rel="noreferrer" title="在钉钉打开"><ExternalLink :size="15" /></a>
        </header>
        <div v-if="expanded.has(workspace.workspaceId)" class="doc-table">
          <div class="doc-row doc-head"><span>文档名称</span><span>类型</span><span>大小</span><span>更新时间</span><span>操作</span></div>
          <div v-for="item in visibleNodes(workspace.children)" :key="item.node.nodeId" class="doc-row">
            <span class="doc-name" :style="{ paddingLeft: `${item.depth * 22}px` }">
              <button v-if="item.node.type === 'FOLDER'" class="tree-toggle small" type="button" :title="expanded.has(item.node.nodeId) ? '收起目录' : '展开目录'" @click="toggle(item.node.nodeId)"><ChevronDown v-if="expanded.has(item.node.nodeId)" :size="15" /><ChevronRight v-else :size="15" /></button>
              <span v-else class="tree-spacer"></span>
              <span class="doc-icon" :class="item.node.type === 'FOLDER' ? 'folder' : 'file'"><Folder v-if="item.node.type === 'FOLDER'" :size="16" /><File v-else :size="16" /></span>
              <span class="doc-title"><strong>{{ item.node.name }}</strong><small>{{ item.node.nodeId }}</small></span>
            </span>
            <span>{{ item.node.extension?.toUpperCase() || item.node.category || item.node.type }}</span>
            <span>{{ formatBytes(item.node.size) }}</span>
            <span>{{ formatDate(item.node.modifiedTime) }}</span>
            <span><a v-if="item.node.url" class="open-link" :href="item.node.url" target="_blank" rel="noreferrer" title="在钉钉打开"><ExternalLink :size="14" /></a><span v-else>-</span></span>
          </div>
          <div v-if="!workspace.children.length" class="space-empty">该知识库暂无文档。</div>
        </div>
      </section>
    </div>
  </section>
</template>
