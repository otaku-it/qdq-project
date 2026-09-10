import type { Blueprint, LauncherJob, UserRole } from './types'

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
}
