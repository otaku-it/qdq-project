<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { AlertTriangle, BookOpenCheck, Building2, Check, Play, Sparkles, UserRound, X } from '@lucide/vue'
import type { Blueprint, UserRole } from '../types'

const props = defineProps<{ blueprint: Blueprint; submitting: boolean }>()
const emit = defineEmits<{ submit: [payload: { tenantName: string; operatorName: string; role: UserRole; larkUser: string; selectedTasks: string[]; selectedSkills: string[]; simulateFailure: boolean }] }>()

const form = reactive({
  tenantName: '智灵电商演示租户',
  operatorName: '辛海',
  role: 'ADMIN' as UserRole,
  larkUser: '辛海',
  selectedTasks: props.blueprint.tasks.map((task) => task.id),
  selectedSkills: ['project-plan', 'requirement-analysis'],
  simulateFailure: false,
})
const skillsModalOpen = ref(false)
const draftSkills = ref<string[]>([])

const hasSkillsTask = computed(() => form.selectedTasks.includes('agent-skills'))
const canSubmit = computed(() => form.tenantName.trim().length > 0
  && form.operatorName.trim().length > 0
  && form.larkUser.trim().length > 0
  && form.selectedTasks.length > 0
  && (!hasSkillsTask.value || form.selectedSkills.length > 0)
  && !props.submitting)

function setRole(role: UserRole) {
  form.role = role
  form.operatorName = role === 'ADMIN' ? '辛海' : '小王'
  form.larkUser = role === 'ADMIN' ? '辛海' : '小王'
}

function toggle(list: string[], id: string) {
  const index = list.indexOf(id)
  if (index >= 0) list.splice(index, 1)
  else list.push(id)
}

function openSkillsModal() {
  draftSkills.value = [...form.selectedSkills]
  skillsModalOpen.value = true
}

function closeSkillsModal() {
  skillsModalOpen.value = false
}

function toggleDraftSkill(id: string) {
  toggle(draftSkills.value, id)
}

function confirmSkills() {
  form.selectedSkills = [...draftSkills.value]
  const taskIndex = form.selectedTasks.indexOf('agent-skills')
  if (form.selectedSkills.length === 0 && taskIndex >= 0) form.selectedTasks.splice(taskIndex, 1)
  if (form.selectedSkills.length > 0 && taskIndex < 0) form.selectedTasks.push('agent-skills')
  closeSkillsModal()
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    tenantName: form.tenantName.trim(),
    operatorName: form.operatorName.trim(),
    role: form.role,
    larkUser: form.larkUser.trim(),
    selectedTasks: [...form.selectedTasks],
    selectedSkills: hasSkillsTask.value ? [...form.selectedSkills] : [],
    simulateFailure: form.simulateFailure,
  })
}
</script>

<template>
  <section class="workspace">
    <div class="section-heading">
      <div><p class="eyebrow">FEISHU LAUNCHER</p><h1>启动任务</h1></div>
      <span class="version-tag">方案 {{ blueprint.version }}</span>
    </div>
    <form class="setup-form" @submit.prevent="submit">
      <div class="form-section">
        <div class="form-section-title"><h2>当前登录身份</h2><span>租户与组织架构已由中台完成同步</span></div>
        <div class="role-switch" role="group" aria-label="当前登录身份">
          <button type="button" :class="{ active: form.role === 'ADMIN' }" @click="setRole('ADMIN')"><Building2 :size="16" />企业管理员</button>
          <button type="button" :class="{ active: form.role === 'USER' }" @click="setRole('USER')"><UserRound :size="16" />普通用户</button>
        </div>
        <div class="input-grid launcher-input-grid">
          <label class="field"><span>企业租户</span><span class="input-shell"><Building2 :size="17" /><input v-model="form.tenantName" maxlength="80" autocomplete="organization" /></span></label>
          <label class="field"><span>中台当前用户</span><span class="input-shell"><UserRound :size="17" /><input v-model="form.operatorName" maxlength="80" autocomplete="name" /></span></label>
          <label class="field"><span>飞书用户</span><span class="input-shell"><UserRound :size="17" /><input v-model="form.larkUser" maxlength="80" autocomplete="name" /></span></label>
        </div>
      </div>
      <div class="form-section">
        <div class="form-section-title"><h2>选择启动任务</h2><span>{{ form.selectedTasks.length }}/{{ blueprint.tasks.length }} 已选择</span></div>
        <div class="task-grid">
          <label v-for="task in blueprint.tasks.filter((item) => item.id !== 'agent-skills')" :key="task.id" class="task-option" :class="{ selected: form.selectedTasks.includes(task.id) }">
            <input type="checkbox" :checked="form.selectedTasks.includes(task.id)" @change="toggle(form.selectedTasks, task.id)" />
            <span class="custom-checkbox" aria-hidden="true"></span>
            <span class="task-icon"><BookOpenCheck v-if="task.id === 'knowledge-sync'" :size="18" /><Sparkles v-else :size="18" /></span>
            <span class="task-copy"><strong>{{ task.name }}</strong><small>{{ task.description }}</small></span>
            <span class="task-scope">{{ task.scope }}</span>
          </label>
          <button v-for="task in blueprint.tasks.filter((item) => item.id === 'agent-skills')" :key="task.id" class="task-option skill-task-trigger" :class="{ selected: hasSkillsTask }" type="button" :aria-pressed="hasSkillsTask" @click="openSkillsModal">
            <span class="custom-checkbox" aria-hidden="true"></span>
            <span class="task-icon"><Sparkles :size="18" /></span>
            <span class="task-copy"><strong>{{ task.name }}</strong><small>{{ hasSkillsTask ? `已选择 ${form.selectedSkills.length} 个 Skills，点击调整` : '点击选择要上传或初始化的 Skills' }}</small></span>
            <span class="task-scope">{{ task.scope }}</span>
          </button>
        </div>
      </div>
      <div class="form-footer">
        <label class="drill-option"><input v-model="form.simulateFailure" type="checkbox" /><span class="switch" aria-hidden="true"></span><span><strong>故障演练</strong><small><AlertTriangle :size="14" /> 模拟外部适配器首次调用超时</small></span></label>
        <button class="primary-button" type="submit" :disabled="!canSubmit"><Play :size="17" fill="currentColor" />{{ submitting ? '正在启动' : '启动任务树' }}</button>
      </div>
    </form>
  </section>
  <div v-if="skillsModalOpen" class="modal-backdrop" role="presentation" @click.self="closeSkillsModal">
    <section class="skills-modal" role="dialog" aria-modal="true" aria-labelledby="skills-modal-title" @keydown.esc="closeSkillsModal">
      <header class="modal-header"><div><p class="eyebrow">AGENT SKILLS</p><h2 id="skills-modal-title">选择 Agent Skills</h2><span>上传或初始化前，确认本次需要执行的 Skills。</span></div><button class="icon-button" type="button" title="关闭" @click="closeSkillsModal"><X :size="18" /></button></header>
      <div class="modal-body"><div class="form-section-title"><h2>可用 Skills</h2><span>{{ draftSkills.length }}/{{ blueprint.skills.length }} 已选择</span></div><div class="skill-grid modal-skill-grid"><label v-for="skill in blueprint.skills" :key="skill.id" class="skill-option" :class="{ selected: draftSkills.includes(skill.id) }"><input type="checkbox" :checked="draftSkills.includes(skill.id)" @change="toggleDraftSkill(skill.id)" /><span class="custom-checkbox" aria-hidden="true"></span><span class="skill-copy"><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small></span><span class="skill-category">{{ skill.category }}</span></label></div></div>
      <footer class="modal-footer"><button class="secondary-button" type="button" @click="closeSkillsModal">取消</button><button class="primary-button" type="button" @click="confirmSkills"><Check :size="17" />{{ draftSkills.length ? `确认选择 ${draftSkills.length} 个 Skills` : '移除 Agent Skills 任务' }}</button></footer>
    </section>
  </div>
</template>
