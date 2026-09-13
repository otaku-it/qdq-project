<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, CircleHelp, KeyRound, Link2, LoaderCircle, Save, ShieldCheck } from '@lucide/vue'
import { launcherApi } from '../api'
import type { FeishuMigrationConfig, LoginSession } from '../types'

const props = defineProps<{ session: LoginSession }>()
const form = reactive({ appId: '', appSecret: '', knowledgeBaseUrl: '' })
const config = ref<FeishuMigrationConfig | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')

const statusClass = computed(() => config.value ? `config-status-${config.value.verificationStatus.toLowerCase()}` : '')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    config.value = await launcherApi.getMigrationConfig(props.session.tenantName, props.session.role)
    if (config.value.configured) {
      form.appId = config.value.appId
      form.knowledgeBaseUrl = config.value.knowledgeBaseUrl
    }
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.appId.trim() || !form.appSecret.trim() || !form.knowledgeBaseUrl.trim()) {
    error.value = '请完整填写 app_id、app_secret 和企业知识库地址'
    return
  }
  saving.value = true
  error.value = ''
  try {
    config.value = await launcherApi.saveMigrationConfig({
      tenantName: props.session.tenantName,
      role: props.session.role,
      appId: form.appId,
      appSecret: form.appSecret,
      knowledgeBaseUrl: form.knowledgeBaseUrl,
    })
    form.appSecret = ''
  } catch (cause) {
    error.value = messageFrom(cause)
  } finally {
    saving.value = false
  }
}

function messageFrom(cause: unknown) {
  return cause instanceof Error ? cause.message : '请求失败，请稍后重试'
}
</script>

<template>
  <section class="workspace config-workspace">
    <div class="section-heading">
      <div><p class="eyebrow">FEISHU DOCUMENT MIGRATION</p><h1>飞书文档迁移配置</h1><p class="config-intro">配置企业级飞书应用与目标知识库，后续迁移任务将使用此配置完成文档导入。</p></div>
      <span class="version-tag">租户级配置</span>
    </div>

    <div v-if="error" class="config-error" role="alert"><CircleHelp :size="17" /><span>{{ error }}</span></div>
    <div v-if="loading" class="config-loading"><LoaderCircle class="spinning" :size="22" />正在读取租户配置</div>
    <form v-else class="config-card" @submit.prevent="save">
      <div class="config-card-heading"><div><h2>飞书应用凭据</h2><p>仅保存在后端服务中，app_secret 不会返回或展示在页面。</p></div><KeyRound :size="21" /></div>
      <div class="config-fields">
        <label class="field"><span>app_id</span><div class="input-shell"><KeyRound :size="16" /><input v-model="form.appId" autocomplete="off" placeholder="请输入飞书自建应用 app_id" /></div></label>
        <label class="field"><span>app_secret</span><div class="input-shell"><KeyRound :size="16" /><input v-model="form.appSecret" type="password" autocomplete="new-password" placeholder="请输入应用密钥" /></div></label>
      </div>

      <div class="config-divider"></div>
      <div class="config-card-heading"><div><h2>目标企业知识库</h2><p>粘贴知识库或父目录页面地址，系统会自动提取节点 token 并查询 space_id。</p></div><Link2 :size="21" /></div>
      <label class="field"><span>企业知识库地址</span><div class="input-shell"><Link2 :size="16" /><input v-model="form.knowledgeBaseUrl" type="url" autocomplete="off" placeholder="https://xxx.feishu.cn/wiki/xxxxxxxx" /></div></label>
      <p class="config-hint">支持带查询参数的完整飞书链接，例如 <code>.../wiki/W2Qlww4ncifvIukI7ZPcN0OTnQe</code>。</p>

      <div v-if="config?.configured" class="config-result" :class="statusClass">
        <div class="config-result-title"><CheckCircle2 v-if="config.verificationStatus === 'VERIFIED'" :size="18" /><ShieldCheck v-else :size="18" /><strong>{{ config.verificationStatus === 'VERIFIED' ? '配置校验成功' : '配置已保存' }}</strong><span>{{ config.verificationMessage }}</span></div>
        <dl><div><dt>parent_wiki_token</dt><dd>{{ config.parentWikiToken || '未解析' }}</dd></div><div><dt>space_id</dt><dd>{{ config.spaceId || '待权限校验' }}</dd></div></dl>
      </div>

      <footer class="config-footer"><span><ShieldCheck :size="15" />建议使用企业自建应用，并将应用加入目标知识库</span><button class="primary-button" type="submit" :disabled="saving"><LoaderCircle v-if="saving" class="spinning" :size="17" /><Save v-else :size="17" />{{ saving ? '正在保存并校验' : '保存配置并校验' }}</button></footer>
    </form>
  </section>
</template>
