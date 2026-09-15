<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Check, CircleHelp, FileText, FileUp, Pencil, Plus, Power, PowerOff, RefreshCw, Save, Trash2, X } from '@lucide/vue'
import { launcherApi } from '../api'
import type { AgentSkill, LoginSession } from '../types'

const props = defineProps<{ session: LoginSession }>()
const skills = ref<AgentSkill[]>([])
const loading = ref(true)
const busy = ref(false)
const statusChangingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const error = ref('')
const success = ref('')
const uploadError = ref('')
const modalOpen = ref(false)
const file = ref<File | null>(null)
const form = ref({ skillCode: '', displayName: '', description: '', defaultPrompt: '', category: '通用' })
const editingSkill = ref<AgentSkill | null>(null)

onMounted(load)
async function load() {
  loading.value = true; error.value = ''
  try { skills.value = await launcherApi.getAgentSkills('ADMIN') } catch (e) { error.value = e instanceof Error ? e.message : '加载 Skills 失败' } finally { loading.value = false }
}
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] ?? null }
function openUpload() { error.value = ''; uploadError.value = ''; success.value = ''; editingSkill.value = null; form.value = { skillCode: '', displayName: '', description: '', defaultPrompt: '', category: '通用' }; file.value = null; modalOpen.value = true }
function openEdit(skill: AgentSkill) { error.value = ''; uploadError.value = ''; success.value = ''; editingSkill.value = skill; form.value = { skillCode: skill.skillCode || skill.id, displayName: skill.displayName || skill.name, description: skill.description || '', defaultPrompt: skill.defaultPrompt || '', category: skill.category || '通用' }; file.value = null; modalOpen.value = true }
function closeUpload() { if (!busy.value) { uploadError.value = ''; editingSkill.value = null; modalOpen.value = false } }
async function upload() {
  if (!form.value.skillCode || !form.value.displayName || (!editingSkill.value && !file.value)) { uploadError.value = editingSkill.value ? '请填写中文名称' : '请填写 Skill 编码、中文名称并选择 Skill 文件'; return }
  busy.value = true; uploadError.value = ''; success.value = ''
  try {
    if (editingSkill.value) {
      await launcherApi.updateAgentSkill(Number(editingSkill.value.id), form.value, 'ADMIN')
      success.value = 'Skill 信息已保存'
    } else {
      await launcherApi.uploadAgentSkill({ ...form.value, file: file.value!, role: 'ADMIN', tenantId: props.session.tenantName })
      success.value = 'Skill 上传成功，已写入公共目录'
    }
    modalOpen.value = false; editingSkill.value = null; await load()
  } catch (e) { uploadError.value = e instanceof Error ? e.message : `${editingSkill.value ? '保存' : '上传'}失败，请稍后重试` } finally { busy.value = false }
}
async function changeStatus(skill: AgentSkill) {
  if (!skill.id) return
  const enable = skill.status !== 'ACTIVE'
  const action = enable ? '启用' : '停用'
  if (!window.confirm(`确定${action}「${skill.displayName || skill.name}」吗？`)) return
  statusChangingId.value = skill.id
  try { await launcherApi.changeAgentSkillStatus(Number(skill.id), enable ? 'ACTIVE' : 'DISABLED', 'ADMIN'); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : `${action}失败` }
  finally { statusChangingId.value = null }
}
async function remove(skill: AgentSkill) {
  if (!skill.id || !window.confirm(`确定删除「${skill.displayName || skill.name}」吗？删除后将不再出现在管理和启动器列表中。`)) return
  deletingId.value = skill.id
  try { await launcherApi.deleteAgentSkill(Number(skill.id), 'ADMIN'); success.value = 'Skill 已删除，文件和版本记录已保留'; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '删除失败' }
  finally { deletingId.value = null }
}
</script>

<template>
  <section class="workspace">
    <div class="section-heading"><div><p class="eyebrow">PUBLIC CATALOG</p><h1>Agent Skills 管理</h1><p class="section-subtitle">公共 Skill 对所有租户可用，管理员上传后会保存到服务器 Skills 目录并记录版本。</p></div><div class="section-actions"><button class="secondary-button" type="button" @click="load"><RefreshCw :size="15" />刷新</button><button class="primary-button" type="button" @click="openUpload"><Plus :size="16" />上传 Skill</button></div></div>
    <div v-if="error" class="global-error"><CircleHelp :size="18" /><span>{{ error }}</span><button type="button" @click="error = ''">×</button></div>
    <div v-if="success" class="success-banner"><Check :size="17" /><span>{{ success }}</span></div>
    <div class="skills-admin-panel">
      <div class="panel-heading"><strong>公共 Skills</strong><span>{{ skills.length }} 个</span></div>
      <div v-if="loading" class="empty-state">正在加载 Skill 目录…</div>
      <div v-else-if="!skills.length" class="empty-state">暂未上传 Skill，请点击右上角上传。</div>
      <div v-else class="skills-admin-table"><div class="skills-admin-row skills-admin-head"><span>名称</span><span>编码</span><span>分类</span><span>状态</span><span>操作</span></div><div v-for="skill in skills" :key="skill.id" class="skills-admin-row" :class="{ 'skills-admin-row-disabled': skill.status === 'DISABLED' }"><span><strong>{{ skill.displayName || skill.name }}</strong><small>{{ skill.description }}</small></span><code>{{ skill.skillCode || skill.id }}</code><span>{{ skill.category }}</span><span class="capability-status" :class="skill.status === 'DISABLED' ? 'capability-disabled' : 'capability-ready'">{{ skill.status || 'ACTIVE' }}</span><span class="skills-admin-actions"><button v-if="skill.status !== 'DISABLED'" class="icon-button disable-icon" type="button" title="停用 Skill" :disabled="statusChangingId === skill.id || deletingId === skill.id" @click="changeStatus(skill)"><PowerOff :size="15" /></button><button v-else class="icon-button enable-icon" type="button" title="启用 Skill" :disabled="statusChangingId === skill.id || deletingId === skill.id" @click="changeStatus(skill)"><Power :size="15" /></button><button class="icon-button" type="button" title="编辑 Skill" :disabled="deletingId === skill.id" @click="openEdit(skill)"><Pencil :size="15" /></button><button class="icon-button danger-icon" type="button" title="删除 Skill" :disabled="deletingId === skill.id" @click="remove(skill)"><Trash2 :size="15" /></button></span></div></div>
    </div>
  </section>
  <div v-if="modalOpen" class="modal-backdrop" @click.self="closeUpload">
    <section class="skills-modal" role="dialog" aria-modal="true" aria-labelledby="skill-upload-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">UPLOAD PACKAGE</p>
          <h2 id="skill-upload-title">{{ editingSkill ? '编辑公共 Agent Skill' : '上传公共 Agent Skill' }}</h2>
          <span>{{ editingSkill ? '可修改展示信息与默认提示词，Skill 编码、文件和版本保持不变。' : '支持根目录包含 SKILL.md 的 .zip / .skill，或单个 SKILL.md。' }}</span>
        </div>
        <button class="icon-button" type="button" aria-label="关闭上传弹框" @click="closeUpload"><X :size="18" /></button>
      </header>
      <div class="modal-body">
        <div v-if="uploadError" class="modal-error" role="alert"><CircleHelp :size="16" /><span>{{ uploadError }}</span></div>
        <div class="upload-form-heading">
          <div class="upload-form-icon"><FileText :size="17" /></div>
          <div><strong>Skill 基础信息</strong><p>填写用于启动器展示和任务选择的公共目录信息。</p></div>
        </div>
        <div class="skill-upload-grid">
          <label class="field"><span>Skill 编码 <em>必填</em></span><input v-model="form.skillCode" :readonly="Boolean(editingSkill)" placeholder="buyer-show-analysis" maxlength="64" /><small>{{ editingSkill ? '编码与已上传文件关联，编辑时不可修改。' : '建议使用小写英文、数字和连字符。' }}</small></label>
          <label class="field"><span>中文名称 <em>必填</em></span><input v-model="form.displayName" placeholder="如：买家秀场景分析" maxlength="128" /><small>此名称将显示在任务选择列表中。</small></label>
          <label class="field skill-description-field"><span>描述 <i>选填</i></span><textarea v-model="form.description" rows="3" maxlength="1000" placeholder="简要说明该 Skill 可解决的问题或适用场景" /></label>
          <label class="field skill-default-prompt-field"><span>默认提示词 <i>选填</i></span><textarea v-model="form.defaultPrompt" rows="4" maxlength="4000" placeholder="填写此 Skill 在豆包工作台初始化或执行时默认使用的提示词" /><small>仅保存为 Skill 默认配置，不会覆盖用户后续在任务会话中的输入。</small></label>
          <label class="field"><span>分类</span><input v-model="form.category" maxlength="64" placeholder="如：电商运营" /></label>
        </div>
        <label v-if="!editingSkill" class="upload-drop" :class="{ 'has-file': file }">
          <span class="upload-drop-icon"><FileUp :size="22" /></span>
          <span class="upload-drop-copy"><strong>{{ file ? '已选择文件' : '选择 Skill 文件' }}</strong><small>{{ file ? file.name : '支持 .zip、.skill 或 SKILL.md 格式' }}</small></span>
          <span class="upload-drop-action">浏览文件</span>
          <input type="file" accept=".zip,.skill,.md" @change="chooseFile" />
        </label>
      </div>
      <footer class="modal-footer"><button class="secondary-button" type="button" @click="closeUpload">取消</button><button class="primary-button" type="button" :disabled="busy" @click="upload"><component :is="editingSkill ? Save : FileUp" :size="16" />{{ busy ? (editingSkill ? '正在保存…' : '正在上传…') : (editingSkill ? '保存修改' : '确认上传') }}</button></footer>
    </section>
  </div>
</template>
