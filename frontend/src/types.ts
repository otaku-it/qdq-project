export type UserRole = 'ADMIN' | 'USER'

export interface LoginSession {
  tenantName: string
  operatorName: string
  role: UserRole
  larkUser: string
}

export interface LauncherTask {
  id: string
  name: string
  description: string
  scope: string
}

export interface AgentSkill {
  id: string
  name: string
  description: string
  defaultPrompt?: string
  category: string
  skillCode?: string
  displayName?: string
  currentVersionId?: number | null
  status?: string
}

export interface Blueprint {
  version: string
  tasks: LauncherTask[]
  skills: AgentSkill[]
}

export type JobStatus = 'RUNNING' | 'NEEDS_USER_ACTION' | 'SUCCEEDED' | 'FAILED'
export type StepStatus = 'PENDING' | 'RUNNING' | 'WAITING_USER' | 'SUCCEEDED' | 'FAILED'
export type DeliveryStatus = 'PENDING' | 'PROVISIONING' | 'WAITING_AUTHORIZATION' | 'READY' | 'FAILED'

export interface LauncherStep {
  id: string
  title: string
  description: string
  status: StepStatus
  requiresUserAction: boolean
  executionMode: 'PLAYWRIGHT' | 'SIMULATED' | null
  resultMessage: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface JobEvent {
  timestamp: string
  level: 'info' | 'success' | 'warning' | 'error'
  message: string
}

export interface Delivery {
  scope: '企业级' | '个人级'
  name: string
  description: string
  status: DeliveryStatus
}

export interface LauncherJob {
  id: string
  tenantName: string
  operatorName: string
  role: UserRole
  larkUser: string
  selectedTasks: string[]
  selectedSkills: string[]
  simulateFailure: boolean
  status: JobStatus
  progress: number
  currentAction: string | null
  startedAt: string
  updatedAt: string
  deliveries: Delivery[]
  steps: LauncherStep[]
  events: JobEvent[]
}

export interface DingTalkUserInfo {
  userid: string
  unionId: string
  name: string
  avatar: string | null
}

export interface KnowledgeDocNode {
  nodeId: string
  name: string
  type: string
  category: string | null
  extension: string | null
  hasChildren: boolean
  url: string | null
  spaceId: string | null
  dentryId: string | null
  fileId: string | null
  downloadUrl: string | null
  downloadExpiresAt: string | null
  downloadAvailable: boolean
  creatorId: string | null
  modifierId: string | null
  createTime: string | null
  modifiedTime: string | null
  size: number | null
  children: KnowledgeDocNode[]
}

export interface KnowledgeWorkspace {
  workspaceId: string
  name: string
  type: 'PERSONAL' | 'TEAM' | string
  rootNodeId: string | null
  description: string | null
  url: string | null
  creatorId: string | null
  permissionRole: string | null
  createTime: string | null
  modifiedTime: string | null
  children: KnowledgeDocNode[]
}

export interface KnowledgeDocList {
  user: DingTalkUserInfo
  personalDocs: KnowledgeWorkspace | null
  enterpriseDocs: KnowledgeWorkspace[]
  totalDocCount: number
  filteredByFileName: string | null
}
