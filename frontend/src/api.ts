import type { AgentSkill, Blueprint, LauncherJob, UserRole } from './types'

const API_BASE = '/api/v1/launcher'

interface CreateJobPayload {
  tenantName: string
  operatorName: string
  role: UserRole
  larkUser: string
  selectedTasks: string[]
  selectedSkills: string[]
  simulateFailure: boolean
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const launcherApi = {
  getBlueprint: () => request<Blueprint>('/blueprint'),
  createJob: (payload: CreateJobPayload) => request<LauncherJob>('/jobs', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  getJob: (id: string) => request<LauncherJob>(`/jobs/${id}`),
  continueJob: (id: string) => request<LauncherJob>(`/jobs/${id}/continue`, { method: 'POST' }),
  retryJob: (id: string) => request<LauncherJob>(`/jobs/${id}/retry`, { method: 'POST' }),
  getAgentSkills: async (role: UserRole, tenantId?: string) => {
    const query = new URLSearchParams({ role })
    if (tenantId) query.set('tenantId', tenantId)
    const response = await fetch(`/api/v1/agent-skills?${query.toString()}`)
    if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`) }
    const data = await response.json() as { skills: AgentSkill[] }
    return data.skills
  },
  uploadAgentSkill: async (payload: { role: UserRole; tenantId: string; operatorId?: number; skillCode: string; displayName: string; description: string; defaultPrompt: string; category: string; file: File }) => {
    const form = new FormData()
    form.set('role', payload.role); form.set('tenantId', payload.tenantId)
    if (payload.operatorId) form.set('operatorId', String(payload.operatorId))
    form.set('skillCode', payload.skillCode); form.set('displayName', payload.displayName)
    form.set('description', payload.description); form.set('defaultPrompt', payload.defaultPrompt); form.set('category', payload.category); form.set('file', payload.file)
    const response = await fetch(`/api/v1/agent-skills`, { method: 'POST', headers: { 'X-Role': payload.role }, body: form })
    if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`) }
    return response.json() as Promise<AgentSkill>
  },
  deleteAgentSkill: async (id: number, role: UserRole, operatorId?: number) => {
    const query = new URLSearchParams({ role }); if (operatorId) query.set('operatorId', String(operatorId))
    const response = await fetch(`/api/v1/agent-skills/${id}?${query.toString()}`, { method: 'DELETE', headers: { 'X-Role': role } })
    if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`) }
  },
  updateAgentSkill: async (id: number, payload: { displayName: string; description: string; defaultPrompt: string; category: string }, role: UserRole, operatorId?: number) => {
    const query = new URLSearchParams({ role }); if (operatorId) query.set('operatorId', String(operatorId))
    const response = await fetch(`/api/v1/agent-skills/${id}?${query.toString()}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Role': role }, body: JSON.stringify(payload) })
    if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`) }
    return response.json() as Promise<AgentSkill>
  },
  changeAgentSkillStatus: async (id: number, status: 'ACTIVE' | 'DISABLED', role: UserRole, operatorId?: number) => {
    const query = new URLSearchParams({ role, status }); if (operatorId) query.set('operatorId', String(operatorId))
    const response = await fetch(`/api/v1/agent-skills/${id}/status?${query.toString()}`, { method: 'PATCH', headers: { 'X-Role': role } })
    if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || `请求失败 (${response.status})`) }
    return response.json() as Promise<AgentSkill>
  },
}
