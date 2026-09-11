import { chromium } from 'playwright-core'
import { resolve } from 'node:path'

const WORK_URL = process.env.DOUBAO_WORK_URL || 'https://www.doubao.com/chat/skills?channel=RYQ5f'
const WORK_ORIGIN = new URL(WORK_URL).origin
const CDP_ENDPOINT = process.env.DOUBAO_CDP_ENDPOINT
const USER_DATA_DIR = process.env.DOUBAO_USER_DATA_DIR || resolve(process.cwd(), '.doubao-profile')

async function readPayload() {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function ensureCdpTarget(endpoint) {
  const endpointUrl = new URL(endpoint)
  if (!['http:', 'https:'].includes(endpointUrl.protocol)) return
  const origin = endpointUrl.origin
  const response = await fetch(`${origin}/json/list`, { signal: AbortSignal.timeout(3_000) })
  if (!response.ok) throw new Error(`CDP 状态检查失败 (${response.status})`)
  const targets = await response.json()
  if (Array.isArray(targets) && targets.some((target) => target.type === 'page')) return

  const createResponse = await fetch(`${origin}/json/new?${encodeURIComponent(WORK_URL)}`, {
    method: 'PUT',
    signal: AbortSignal.timeout(5_000),
  })
  if (!createResponse.ok) throw new Error(`CDP 无可用页面且自动新建页面失败 (${createResponse.status})`)
}

async function openBrowser() {
  let cdpFailure
  if (CDP_ENDPOINT) {
    try {
      await ensureCdpTarget(CDP_ENDPOINT)
      const browser = await chromium.connectOverCDP(CDP_ENDPOINT)
      const context = browser.contexts()[0]
      if (!context) throw new Error('CDP 浏览器没有可用上下文')
      return { context, ownsContext: false }
    } catch (error) {
      cdpFailure = error
    }
  }

  try {
    const context = await chromium.launchPersistentContext(USER_DATA_DIR, {
      channel: 'chrome',
      headless: false,
    })
    return { context, ownsContext: true }
  } catch (error) {
    const cdpMessage = cdpFailure instanceof Error ? `CDP 连接失败：${cdpFailure.message}；` : ''
    const launchMessage = error instanceof Error ? error.message : String(error)
    throw new Error(`${cdpMessage}浏览器自动启动失败：${launchMessage}`)
  }
}

async function findLoginPage(context) {
  for (const page of context.pages()) {
    if (/accounts\.feishu\.cn|passport\.feishu\.cn|login/i.test(page.url())) return page
    const loginPrompt = page.getByText(/扫码授权|扫描二维码|登录飞书账号|登录豆包/).first()
    if (await loginPrompt.isVisible().catch(() => false)) return page
  }
  return null
}

function isDoubaoTaskUrl(value) {
  try {
    const url = new URL(value)
    return url.origin === WORK_ORIGIN
      && /^\/chat\/(?!skills(?:\/|$)|cron-jobs(?:\/|$)|drive(?:\/|$))[^/]+\/?$/.test(url.pathname)
  } catch {
    return false
  }
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function findExistingTask(context, marker) {
  for (const page of context.pages()) {
    if (!isDoubaoTaskUrl(page.url())) continue
    const markerElement = page.getByText(marker, { exact: false }).last()
    if (await markerElement.isVisible().catch(() => false)) {
      await page.bringToFront()
      return page
    }
  }
  return null
}

async function openWorkPage(context) {
  let page = context.pages().find((candidate) => candidate.url().startsWith(`${WORK_ORIGIN}/chat`))
  if (!page) page = await context.newPage()
  if (!page.url().startsWith(`${WORK_ORIGIN}/chat`)) {
    await page.goto(WORK_URL, { waitUntil: 'domcontentloaded' })
  }
  await page.bringToFront()
  await page.waitForTimeout(1_000)
  return page
}

async function findComposer(page) {
  const candidates = page.locator('[contenteditable="true"]')
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    const count = await candidates.count()
    for (let index = count - 1; index >= 0; index -= 1) {
      const candidate = candidates.nth(index)
      if (await candidate.isVisible().catch(() => false)) return candidate
    }
    await page.waitForTimeout(200)
  }
  throw new Error('未找到豆包工作任务输入框')
}

async function openNewTask(page) {
  const newTask = page.getByText('新工作任务', { exact: true }).first()
  await newTask.waitFor({ state: 'visible', timeout: 20_000 })
  await newTask.click()
  const moreSkills = page.getByRole('button', { name: '更多技能', exact: true })
  await moreSkills.waitFor({ state: 'visible', timeout: 20_000 })
  await findComposer(page)
}

async function selectEnterpriseSkill(page, skillId) {
  const composer = await findComposer(page)
  const attached = composer.getByRole('button', { name: skillId, exact: true })
  if (await attached.isVisible().catch(() => false)) return

  await page.getByRole('button', { name: '更多技能', exact: true }).click()
  const listbox = page.getByRole('listbox')
  await listbox.waitFor({ state: 'visible', timeout: 10_000 })

  // 同一技能可能同时出现在“常用”和“企业”分组，必须限定企业分组避免选错来源。
  const enterpriseGroup = listbox.getByRole('group', { name: /^企业(?:\s*\(\d+\))?$/ })
  await enterpriseGroup.waitFor({ state: 'visible', timeout: 10_000 })
  const option = enterpriseGroup.getByRole('option', {
    name: new RegExp(`^${escapeRegex(skillId)}(?:\\s|$)`),
  })
  if (await option.count() === 0) {
    throw new Error(`豆包“企业”技能分组中未找到 ${skillId}，请确认管理员已完成预置`)
  }
  await option.first().click()

  const updatedComposer = await findComposer(page)
  await updatedComposer.getByRole('button', { name: skillId, exact: true })
    .waitFor({ state: 'visible', timeout: 10_000 })
}

async function submitTask(page, prompt, marker, skillIds) {
  for (const skillId of skillIds) await selectEnterpriseSkill(page, skillId)

  const composer = await findComposer(page)
  for (const skillId of skillIds) {
    if (!(await composer.getByRole('button', { name: skillId, exact: true }).isVisible().catch(() => false))) {
      throw new Error(`发送前未检测到已挂载的企业 Skill：${skillId}`)
    }
  }

  // 使用键盘插入文本以保留 contenteditable 中已经挂载的 Skill 标签。
  await composer.click()
  await composer.press('End')
  await page.keyboard.insertText(prompt)
  await composer.press('Enter')

  await page.waitForURL((url) => isDoubaoTaskUrl(url.toString()), { timeout: 30_000 })
  await page.getByText(marker, { exact: false }).last()
    .waitFor({ state: 'visible', timeout: 20_000 })
}

let browserHandle
let workerResult
let workerExitCode = 0
try {
  const payload = await readPayload()
  const skills = Array.isArray(payload.skills) ? [...new Set(payload.skills)] : []
  if (skills.length === 0) throw new Error('没有待初始化的 Agent Skill')
  if (skills.some((skill) => typeof skill !== 'string' || !/^[a-z0-9-]+$/.test(skill))) {
    throw new Error('存在非法的 Agent Skill ID')
  }
  if (typeof payload.idempotencyKey !== 'string' || payload.idempotencyKey.length === 0) {
    throw new Error('缺少初始化任务幂等键')
  }

  const marker = `[智灵启动器任务 ${payload.idempotencyKey}]`
  const prompt = `${marker}\n请使用本任务已挂载的企业 Skills 完成初始化，并确认能力已就绪。已选择技能：${skills.join('、')}。`
  browserHandle = await openBrowser()

  const existingTask = await findExistingTask(browserHandle.context, marker)
  if (existingTask) {
    workerResult = {
      success: true,
      requiresUserAction: false,
      message: `已复用本次启动器创建的豆包工作任务，并验证 ${skills.length} 个企业 Skill`,
      initializedSkillIds: skills,
      taskUrl: existingTask.url(),
    }
  } else {
    const page = await openWorkPage(browserHandle.context)
    const loginPage = await findLoginPage(browserHandle.context)
    if (loginPage) {
      await loginPage.bringToFront()
      workerResult = {
        success: false,
        requiresUserAction: true,
        message: '受控浏览器已打开，请完成飞书或豆包扫码登录后点击“已完成扫码登录”',
        initializedSkillIds: [],
        taskUrl: null,
      }
    } else {
      await openNewTask(page)
      await submitTask(page, prompt, marker, skills)
      workerResult = {
        success: true,
        requiresUserAction: false,
        message: `已创建豆包工作任务并挂载 ${skills.length} 个企业 Skill：${page.url()}`,
        initializedSkillIds: skills,
        taskUrl: page.url(),
      }
    }
  }
} catch (error) {
  const errorMessage = error instanceof Error ? error.message : String(error)
  const browserUnavailable = /Target page, context or browser has been closed|ECONNREFUSED|connectOverCDP|浏览器自动启动失败/.test(errorMessage)
  workerResult = {
    success: false,
    requiresUserAction: browserUnavailable,
    message: browserUnavailable
      ? '受控浏览器已关闭或会话已失效，已尝试重新打开；请完成登录后继续'
      : errorMessage,
    initializedSkillIds: [],
    taskUrl: null,
  }
  workerExitCode = browserUnavailable ? 0 : 1
} finally {
  if (browserHandle?.ownsContext) await browserHandle.context.close().catch(() => {})
}

await new Promise((resolve, reject) => {
  process.stdout.write(JSON.stringify(workerResult), (error) => error ? reject(error) : resolve())
})
process.exit(workerExitCode)
