import { resolve } from 'node:path'
import { NAVIGATION_TIMEOUT_MS, openDoubaoBrowser } from './doubao-browser.mjs'

const WORK_URL = process.env.DOUBAO_WORK_URL || 'https://www.doubao.com/chat/skills?channel=RYQ5f'
const WORK_ORIGIN = new URL(WORK_URL).origin
const USER_DATA_DIR = process.env.DOUBAO_USER_DATA_DIR || resolve(process.cwd(), '.doubao-profile')

async function readPayload() {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

async function openBrowser() {
  return openDoubaoBrowser({ targetUrl: WORK_URL, userDataDir: USER_DATA_DIR })
}

function isDoubaoWorkPage(page) {
  try {
    const url = new URL(page.url())
    return url.origin === WORK_ORIGIN && url.pathname.startsWith('/chat')
  } catch {
    return false
  }
}

function isAuthorizationOrigin(value) {
  try {
    const url = new URL(value)
    return url.origin === WORK_ORIGIN
      || url.hostname === 'accounts.feishu.cn'
      || url.hostname === 'passport.feishu.cn'
  } catch {
    return false
  }
}

async function closeDuplicateWorkPages(context, preferredPage = null) {
  const pages = context.pages().filter(isDoubaoWorkPage)
  const activePage = preferredPage && pages.includes(preferredPage) ? preferredPage : pages.at(-1)
  if (!activePage) return { page: null, duplicatesClosed: 0 }

  let duplicatesClosed = 0
  for (const duplicatePage of pages) {
    if (duplicatePage === activePage) continue
    try {
      await duplicatePage.close()
      duplicatesClosed += 1
    } catch {
      // 页面可能恰好被用户关闭，下一次运行时会重新归一化当前会话。
    }
  }
  return { page: activePage, duplicatesClosed }
}

async function findAuthorizationPage(context) {
  for (const page of context.pages()) {
    if (!isAuthorizationOrigin(page.url())) continue
    if (/^https?:\/\/(accounts|passport)\.feishu\.cn(?:\/|$)/i.test(page.url())) return page
    const loginPrompt = page.getByText(/扫码登录|扫码授权|扫描二维码|使用飞书.{0,8}扫码|登录飞书账号|登录豆包/).first()
    if (await loginPrompt.isVisible().catch(() => false)) return page
  }
  return null
}

async function findLoginTrigger(page) {
  const candidates = [
    page.getByRole('button', { name: '登录', exact: true }).first(),
    page.getByRole('link', { name: '登录', exact: true }).first(),
    page.getByText('登录', { exact: true }).first(),
  ]
  for (const candidate of candidates) {
    if (await candidate.isVisible().catch(() => false)) return candidate
  }
  return null
}

async function openLoginIfRequired(context, workPage) {
  const authorizationPage = await findAuthorizationPage(context)
  if (authorizationPage) return authorizationPage

  const loginTrigger = await findLoginTrigger(workPage)
  if (!loginTrigger) return null

  await loginTrigger.click()
  await workPage.waitForTimeout(1_000)
  return await findAuthorizationPage(context) ?? workPage
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

function isPersistedDoubaoTaskUrl(value) {
  if (!isDoubaoTaskUrl(value)) return false
  const taskId = new URL(value).pathname.split('/').filter(Boolean).at(-1)
  return Boolean(taskId && !taskId.startsWith('local_'))
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function findExistingTask(context, marker) {
  for (const page of context.pages()) {
    if (!isDoubaoTaskUrl(page.url())) continue
    const markerElement = page.getByText(marker, { exact: false }).last()
    if (await markerElement.isVisible().catch(() => false)) {
      const normalized = await closeDuplicateWorkPages(context, page)
      await page.bringToFront()
      return { page, duplicatesClosed: normalized.duplicatesClosed }
    }
  }
  return null
}

function skillMarkerPattern(idempotencyKey, skillId) {
  // 豆包会将 Skill 标签与正文拆为相邻节点，并可能吞掉斜杠前的空格，不能依赖完整字符串匹配。
  return new RegExp(
    `智灵启动器任务\\s*${escapeRegex(idempotencyKey)}\\s*\\/\\s*${escapeRegex(skillId)}`,
  )
}

function initializationStorageKey(idempotencyKey) {
  return `zhiling-launcher:initialization:${idempotencyKey}`
}

async function readRecordedTasks(page, idempotencyKey) {
  const stored = await page.evaluate((key) => {
    try {
      return JSON.parse(window.localStorage.getItem(key) ?? '{}')
    } catch {
      return {}
    }
  }, initializationStorageKey(idempotencyKey)).catch(() => ({}))

  return new Map(Object.entries(stored).filter(([skillId, taskUrl]) => (
    typeof skillId === 'string' && typeof taskUrl === 'string' && isPersistedDoubaoTaskUrl(taskUrl)
  )))
}

async function recordTask(page, idempotencyKey, skillId, taskUrl) {
  await page.evaluate(({ key, skillId: id, url }) => {
    let recorded = {}
    try {
      recorded = JSON.parse(window.localStorage.getItem(key) ?? '{}')
    } catch {
      // 记录仅用于失败恢复，损坏的数据不会影响实际初始化。
    }
    window.localStorage.setItem(key, JSON.stringify({ ...recorded, [id]: url }))
  }, { key: initializationStorageKey(idempotencyKey), skillId, url: taskUrl })
}

async function findExistingTaskInSidebar(page, marker, skillId) {
  const expectedTitle = `初始化${skillId}能力`
  const candidateUrls = await page.locator('a[href^="/chat/"]').evaluateAll((links, title) => {
    const candidates = new Map()
    for (const link of links) {
      const href = link.getAttribute('href')
      if (!href || candidates.has(href)) continue
      candidates.set(href, link.textContent?.includes(title) ? 0 : 1)
    }
    return [...candidates.entries()]
      .sort(([, leftPriority], [, rightPriority]) => leftPriority - rightPriority)
      .map(([href]) => href)
  }, expectedTitle).catch(() => [])

  // 失败后刚创建的任务会位于“最近”列表前部，限制扫描范围避免历史会话拖慢整个启动器。
  for (const candidateUrl of candidateUrls.slice(0, 10)) {
    const taskUrl = new URL(candidateUrl, WORK_ORIGIN).toString()
    if (!isDoubaoTaskUrl(taskUrl)) continue
    // 不要在主工作页上跳转历史会话：豆包的 SPA 会重置当前任务草稿和已发送记录，
    // 从而导致后续第二个 Skill 看似执行成功但最终只保留一个发送窗口。
    const candidatePage = await page.context().newPage()
    await candidatePage.goto(taskUrl, { waitUntil: 'domcontentloaded', timeout: NAVIGATION_TIMEOUT_MS }).catch(() => {})
    const markerElement = marker
      ? candidatePage.getByText(marker, { exact: false }).last()
      : null
    const markerVisible = markerElement
      ? await markerElement.isVisible({ timeout: 3_000 }).catch(() => false)
      : false
    // 新版初始化消息正文为空，不能仅凭“初始化{skill}能力”标题恢复任务，否则历史
    // 同名会话会被误认为本次任务已完成，导致选择两个 Skill 时只新建一个窗口。
    // 无正文任务通过 localStorage 幂等记录恢复；旧版任务继续通过 marker 兼容恢复。
    if (markerVisible) {
      await candidatePage.bringToFront()
      return { page: candidatePage, taskUrl: candidatePage.url() }
    }
    await candidatePage.close().catch(() => {})
  }
  return null
}

async function openWorkPage(context) {
  const existing = await closeDuplicateWorkPages(context)
  let page = existing.page
  if (!page) {
    // launchPersistentContext 通常会自带一个 about:blank 页面，优先复用它，避免再创建第二个工作台标签。
    page = context.pages().find((candidate) => {
      const url = candidate.url()
      return url === 'about:blank' || url === ''
    }) ?? await context.newPage()
  }
  if (!page.url().startsWith(`${WORK_ORIGIN}/chat`)) {
    await page.goto(WORK_URL, { waitUntil: 'domcontentloaded', timeout: NAVIGATION_TIMEOUT_MS })
  }
  await page.bringToFront()
  await page.waitForTimeout(1_000)
  const normalized = await closeDuplicateWorkPages(context, page)
  return {
    page,
    duplicatesClosed: existing.duplicatesClosed + normalized.duplicatesClosed,
  }
}

function waitingForLogin(duplicatesClosed = 0) {
  const cleanupMessage = duplicatesClosed > 0
    ? `已关闭 ${duplicatesClosed} 个重复豆包工作台页面；`
    : ''
  return {
    success: false,
    requiresUserAction: true,
    message: `${cleanupMessage}豆包登录窗口已打开，请使用飞书扫码登录后点击“已完成扫码登录”`,
    initializedSkillIds: [],
    taskUrl: null,
  }
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
  const previousUrl = page.url()
  const newTask = page.getByText('新工作任务', { exact: true }).first()
  await newTask.waitFor({ state: 'visible', timeout: 20_000 })
  await newTask.click()
  if (isDoubaoTaskUrl(previousUrl)) {
    // 豆包会缓存旧会话正文，不能以正文隐藏作为新建任务完成条件；路由离开旧任务才是可靠信号。
    await page.waitForURL((url) => url.toString() !== previousUrl, { timeout: 20_000 })
  }
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

async function clearComposerBody(composer) {
  // 切换会话时豆包可能暂时复用旧会话的正文节点；递归删除所有不属于 Skill
  // 标签按钮的文本，确保发送内容严格只有 Skill。
  await composer.evaluate((element) => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    const textNodes = []
    while (walker.nextNode()) textNodes.push(walker.currentNode)
    for (const node of textNodes) {
      const skillButton = node.parentElement?.closest('button')
      if (!skillButton || !element.contains(skillButton)) node.remove()
    }
    element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' }))
  })
}

async function submitTask(page, skillId) {
  await selectEnterpriseSkill(page, skillId)

  const composer = await findComposer(page)
  if (!(await composer.getByRole('button', { name: skillId, exact: true }).isVisible().catch(() => false))) {
    throw new Error(`发送前未检测到已挂载的企业 Skill：${skillId}`)
  }

  // 使用键盘插入文本以保留 contenteditable 中已经挂载的 Skill 标签。
  await composer.click()
  await clearComposerBody(composer)
  await composer.press('End')
  const previousUrl = page.url()
  await composer.press('Enter')

  // 若页面仍停在上一个 /chat/{id}，isDoubaoTaskUrl 会立即成立，必须同时确认路由已切换。
  await page.waitForURL(
    (url) => isDoubaoTaskUrl(url.toString()) && url.toString() !== previousUrl,
    { timeout: 30_000 },
  )
  // 豆包先路由到 /chat/local_*，随后才落盘为可出现在左侧列表中的真实会话 ID。
  // 只有真实 ID 可用于置顶和扫码后的幂等恢复。
  if (!isPersistedDoubaoTaskUrl(page.url())) {
    await page.waitForURL((url) => isPersistedDoubaoTaskUrl(url.toString()), { timeout: 30_000 })
  }
  return page.url()
}

async function pinTask(page, taskUrl) {
  const url = new URL(taskUrl)
  const taskId = url.pathname.split('/').filter(Boolean).at(-1)
  if (!taskId || taskId.startsWith('local_') || !/^[a-zA-Z0-9_-]+$/.test(taskId)) {
    throw new Error(`无法从豆包任务地址识别会话 ID：${taskUrl}`)
  }

  const conversation = page.locator(`a[href^="/chat/${taskId}"]`).first()
  await conversation.waitFor({ state: 'visible', timeout: 20_000 })
  await conversation.hover()

  const menuTrigger = conversation.locator('button[aria-haspopup="menu"]').first()
  await menuTrigger.waitFor({ state: 'visible', timeout: 10_000 })
  await menuTrigger.click()

  const menu = page.locator('[role="menu"]:visible').last()
  await menu.waitFor({ state: 'visible', timeout: 10_000 })
  const pinItem = menu.getByRole('menuitem', { name: /^置顶(?:\s|$)/ }).first()
  if (await pinItem.isVisible().catch(() => false)) {
    await pinItem.click()
    await menu.waitFor({ state: 'hidden', timeout: 10_000 })
    return
  }

  const unpinItem = menu.getByRole('menuitem', { name: /^取消置顶(?:\s|$)/ }).first()
  if (await unpinItem.isVisible().catch(() => false)) {
    await page.keyboard.press('Escape')
    return
  }
  await page.keyboard.press('Escape')
  throw new Error(`豆包会话 ${taskId} 的操作菜单中未找到“置顶”`)
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

  const legacyMarker = `[智灵启动器任务 ${payload.idempotencyKey}]`
  const skillMarker = (skillId) => `[智灵启动器任务 ${payload.idempotencyKey} / ${skillId}]`
  browserHandle = await openBrowser()

  // 最后一个 Skill 的任务存在，说明前面的任务已按顺序发送完成；同时兼容旧版合并任务的幂等标记。
  const existingTask = await findExistingTask(
    browserHandle.context,
    skillMarkerPattern(payload.idempotencyKey, skills.at(-1)),
  )
  const legacyTask = existingTask
    ? null
    : await findExistingTask(browserHandle.context, legacyMarker)
  if (legacyTask) {
    await pinTask(legacyTask.page, legacyTask.page.url())
    workerResult = {
      success: true,
      requiresUserAction: false,
      message: `已复用本次启动器创建的豆包工作任务，并验证 ${skills.length} 个企业 Skill`,
      initializedSkillIds: skills,
      taskUrl: legacyTask.page.url(),
    }
  } else {
    const existingAuthorization = await findAuthorizationPage(browserHandle.context)
    if (existingAuthorization) {
      const normalized = await closeDuplicateWorkPages(
        browserHandle.context,
        isDoubaoWorkPage(existingAuthorization) ? existingAuthorization : null,
      )
      await existingAuthorization.bringToFront()
      workerResult = waitingForLogin(normalized.duplicatesClosed)
    } else {
      const workPage = existingTask
        ? { page: existingTask.page, duplicatesClosed: existingTask.duplicatesClosed }
        : await openWorkPage(browserHandle.context)
      let page = workPage.page
      const recordedTasks = await readRecordedTasks(page, payload.idempotencyKey)
      const loginPage = await openLoginIfRequired(browserHandle.context, page)
      if (loginPage) {
        const normalized = await closeDuplicateWorkPages(
          browserHandle.context,
          isDoubaoWorkPage(loginPage) ? loginPage : page,
        )
        await loginPage.bringToFront()
        workerResult = waitingForLogin(workPage.duplicatesClosed + normalized.duplicatesClosed)
      } else {
        try {
          const taskUrls = []
          for (const skillId of skills) {
            const markerPattern = skillMarkerPattern(payload.idempotencyKey, skillId)
            const recordedTaskUrl = recordedTasks.get(skillId)
            if (recordedTaskUrl) {
              taskUrls.push(recordedTaskUrl)
              continue
            }
            const existingSkillTask = await findExistingTask(browserHandle.context, markerPattern)
              ?? await findExistingTaskInSidebar(page, markerPattern, skillId)
            if (existingSkillTask) {
              page = existingSkillTask.page
              taskUrls.push(page.url())
              recordedTasks.set(skillId, page.url())
              await recordTask(page, payload.idempotencyKey, skillId, page.url())
              continue
            }
            await openNewTask(page)
            // 每个任务只发送已挂载的企业 Skill，不再向豆包任务正文注入额外提示词。
            // Enter 仍需保留，用于触发豆包创建任务会话并生成可持久化的任务 URL。
            const taskUrl = await submitTask(page, skillId)
            taskUrls.push(taskUrl)
            recordedTasks.set(skillId, taskUrl)
            await recordTask(page, payload.idempotencyKey, skillId, taskUrl)
          }
          for (const taskUrl of [...new Set(taskUrls)]) {
            await pinTask(page, taskUrl)
          }
          const finalTaskUrl = page.url()
          workerResult = {
            success: true,
            requiresUserAction: false,
            message: `已创建并置顶 ${taskUrls.length} 个豆包工作任务，每个任务分别初始化 1 个企业 Skill：${skills.join('、')}`,
            initializedSkillIds: skills,
            taskUrl: finalTaskUrl,
          }
        } catch (error) {
          // 登录入口可能在页面异步渲染后才出现。超时前再次检查，未登录应暂停而不是失败。
          const delayedLoginPage = await openLoginIfRequired(browserHandle.context, page)
          if (!delayedLoginPage) throw error
          const normalized = await closeDuplicateWorkPages(
            browserHandle.context,
            isDoubaoWorkPage(delayedLoginPage) ? delayedLoginPage : page,
          )
          await delayedLoginPage.bringToFront()
          workerResult = waitingForLogin(workPage.duplicatesClosed + normalized.duplicatesClosed)
        }
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
  await browserHandle?.releaseLock?.().catch(() => {})
}

await new Promise((resolve, reject) => {
  process.stdout.write(JSON.stringify(workerResult), (error) => error ? reject(error) : resolve())
})
process.exit(workerExitCode)
