<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ArrowRight, Building2, Check, ShieldCheck, UserRound } from '@lucide/vue'
import type { LoginSession, UserRole } from '../types'

const emit = defineEmits<{ login: [session: LoginSession] }>()

const accounts: Record<UserRole, { operatorName: string; larkUser: string; label: string; detail: string }> = {
  ADMIN: {
    operatorName: '辛海',
    larkUser: '辛海',
    label: '企业管理员',
    detail: '管理企业知识库同步与租户 Skills 预置',
  },
  USER: {
    operatorName: '小王',
    larkUser: '小王',
    label: '普通用户',
    detail: '同步个人知识库并初始化豆包 Agent',
  },
}

const form = reactive<LoginSession>({
  tenantName: '智灵电商演示租户',
  role: 'ADMIN',
  operatorName: accounts.ADMIN.operatorName,
  larkUser: accounts.ADMIN.larkUser,
})

const selectedAccount = computed(() => accounts[form.role])
const canLogin = computed(() => form.tenantName.trim().length > 0
  && form.operatorName.trim().length > 0
  && form.larkUser.trim().length > 0)

function selectRole(role: UserRole) {
  form.role = role
  form.operatorName = accounts[role].operatorName
  form.larkUser = accounts[role].larkUser
}

function login() {
  if (!canLogin.value) return
  emit('login', {
    tenantName: form.tenantName.trim(),
    operatorName: form.operatorName.trim(),
    role: form.role,
    larkUser: form.larkUser.trim(),
  })
}
</script>

<template>
  <main class="login-page">
    <header class="login-header">
      <div class="login-brand"><span class="brand-mark">Z</span><span><strong>智灵领航</strong><small>FEISHU LAUNCHER</small></span></div>
      <span class="login-system-state"><span class="live-dot"></span>智灵中台</span>
    </header>

    <section class="login-workspace">
      <div class="login-intro">
        <p class="eyebrow">TENANT LAUNCHER</p>
        <h1>登录启动器</h1>
        <p>使用已同步到智灵中台的企业身份进入任务工作台。</p>
        <div class="login-assurance"><ShieldCheck :size="18" /><span>租户与飞书组织身份已完成关联</span></div>
      </div>

      <form class="login-panel" @submit.prevent="login">
        <div class="login-panel-heading"><div><h2>选择登录身份</h2><p>当前提供两个已同步的演示账号</p></div><span class="version-tag">企业租户</span></div>

        <div class="login-role-list" role="radiogroup" aria-label="登录身份">
          <button v-for="role in (['ADMIN', 'USER'] as UserRole[])" :key="role" type="button" class="login-role-option" :class="{ selected: form.role === role }" role="radio" :aria-checked="form.role === role" @click="selectRole(role)">
            <span class="login-role-icon"><Building2 v-if="role === 'ADMIN'" :size="20" /><UserRound v-else :size="20" /></span>
            <span><strong>{{ accounts[role].label }}</strong><small>{{ accounts[role].detail }}</small></span>
            <span class="login-role-check"><Check v-if="form.role === role" :size="15" /></span>
          </button>
        </div>

        <label class="field login-tenant-field"><span>企业租户</span><span class="input-shell"><Building2 :size="17" /><input v-model="form.tenantName" maxlength="80" autocomplete="organization" /></span></label>

        <div class="selected-login-account">
          <span class="account-avatar">{{ form.operatorName.slice(0, 1) }}</span>
          <span><small>中台用户 / 飞书用户</small><strong>{{ form.operatorName }} / {{ form.larkUser }}</strong></span>
          <span class="account-role">{{ selectedAccount.label }}</span>
        </div>

        <button class="primary-button login-submit" type="submit" :disabled="!canLogin">进入启动器<ArrowRight :size="17" /></button>
      </form>
    </section>
  </main>
</template>
